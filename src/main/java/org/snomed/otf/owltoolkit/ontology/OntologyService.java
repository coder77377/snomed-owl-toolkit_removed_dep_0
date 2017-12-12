/*
 * Copyright 2017 SNOMED International, http://snomed.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.snomed.otf.owltoolkit.ontology;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.domain.Relationship;
import org.snomed.otf.owltoolkit.taxonomy.SnomedTaxonomy;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class OntologyService {

	public static final String SNOMED_IRI = "http://snomed.info/id/";
	public static final String SNOMED_PREFIX = ":";
	public static final String SNOMED_ROLE_GROUP = SNOMED_PREFIX + "roleGroup";
	public static final String SNOMED_ROLE_HAS_MEASUREMENT = SNOMED_PREFIX + "roleHasMeasurement";

	private final OWLOntologyManager manager;
	private OWLDataFactory factory;
	private DefaultPrefixManager prefixManager;
	private final Set<Long> ungroupedAttributes;

	public OntologyService(Set<Long> ungroupedAttributes) {
		this.ungroupedAttributes = ungroupedAttributes;
		manager = OWLManager.createOWLOntologyManager();
		factory = new OWLDataFactoryImpl();
		prefixManager = new DefaultPrefixManager();
		prefixManager.setPrefix(SNOMED_PREFIX, SNOMED_IRI);
	}

	public OWLOntology createOntology(SnomedTaxonomy snomedTaxonomy) throws OWLOntologyCreationException {

		Set<OWLAxiom> axioms = new HashSet<>();

		// Create Axioms of Snomed attributes
		Set<Long> attributeConceptIds = snomedTaxonomy.getAttributeConceptIds();
		for (Long attributeConceptId : attributeConceptIds) {
			for (Relationship relationship : snomedTaxonomy.getStatedRelationships(attributeConceptId)) {
				if (relationship.getTypeId() == Concepts.IS_A_LONG && relationship.getDestinationId() != Concepts.CONCEPT_MODEL_ATTRIBUTE_LONG) {
					axioms.add(factory.getOWLSubObjectPropertyOfAxiom(getOwlObjectProperty(attributeConceptId), getOwlObjectProperty(relationship.getDestinationId())));
				}
			}
		}

		// Create Axioms of all other Snomed concepts
		for (Long conceptId : snomedTaxonomy.getAllConceptIds()) {
			OWLClass conceptClass = getOwlClass(conceptId);

			// Process all concept's relationships
			final Set<OWLClassExpression> terms = new HashSet<>();
			Map<Integer, ExpressionGroup> nonZeroRoleGroups = new TreeMap<>();
			for (Relationship relationship : snomedTaxonomy.getStatedRelationships(conceptId)) {
				int group = relationship.getGroup();
				long typeId = relationship.getTypeId();
				long destinationId = relationship.getDestinationId();
				if (typeId == Concepts.IS_A_LONG) {
					terms.add(getOwlClass(destinationId));
				} else if (group == 0) {
					if (ungroupedAttributes.contains(typeId)) {
						// Special cases
						terms.add(getOwlObjectSomeValuesFrom(typeId, destinationId));
					} else {
						// Self grouped relationships in group 0
						terms.add(getOwlObjectSomeValuesFromGroup(getOwlObjectSomeValuesFrom(typeId, destinationId)));
					}
				} else {
					// Collect statements in the same role group into sets
					nonZeroRoleGroups.computeIfAbsent(group, g -> new ExpressionGroup())
							.addMember(getOwlObjectSomeValuesFrom(typeId, destinationId));
					if (typeId == Concepts.HAS_ACTIVE_INGREDIENT_LONG) {
						nonZeroRoleGroups.get(group).setHasActiveIngredientClassExpression(getOwlObjectSomeValuesFrom(typeId, destinationId));
					}
				}
			}

			// For each role group if there is more than one statement in the group we wrap them in an ObjectIntersectionOf statement
			for (Integer group : nonZeroRoleGroups.keySet()) {
				ExpressionGroup expressionGroup = nonZeroRoleGroups.get(group);
				Set<OWLClassExpression> groupTerms = expressionGroup.getMembers();
				if (expressionGroup.getHasActiveIngredientClassExpression() != null) {
					// If one of the relationships in the group has the type Has Active Ingredient we use roleHasMeasurement rather than roleGroup
					terms.add(getOwlObjectSomeValuesWithPrefix(SNOMED_ROLE_HAS_MEASUREMENT, getOnlyValueOrIntersection(groupTerms)));
					// Repeat the Has Active Ingredient expression outside of the roleHasMeasurement expression
					terms.add(expressionGroup.getHasActiveIngredientClassExpression());
				} else {
					// Write out a group of expressions
					terms.add(getOwlObjectSomeValuesFromGroup(getOnlyValueOrIntersection(groupTerms)));
				}
			}

			if (terms.isEmpty()) {
				// SNOMED CT root concept
				terms.add(factory.getOWLThing());
			}

			if (snomedTaxonomy.isPrimitive(conceptId)) {
				axioms.add(factory.getOWLSubClassOfAxiom(conceptClass, getOnlyValueOrIntersection(terms)));
			} else {
				axioms.add(factory.getOWLEquivalentClassesAxiom(conceptClass, getOnlyValueOrIntersection(terms)));
			}

			// Add raw axioms from the axiom reference set file
			Set<OWLAxiom> conceptAxioms = snomedTaxonomy.getConceptAxiomMap().get(conceptId);
			if (conceptAxioms != null) {
				axioms.addAll(conceptAxioms);
			}
		}

		return manager.createOntology(axioms, IRI.create(SNOMED_IRI));
	}

	public Set<Long> getPropertiesDeclaredAsTransitive(OWLOntology owlOntology) {
		Set<Long> transitiveProperties = new HashSet<>();
		Set<OWLTransitiveObjectPropertyAxiom> transitiveObjectPropertyAxioms = owlOntology.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY);
		for (OWLTransitiveObjectPropertyAxiom transitiveObjectPropertyAxiom : transitiveObjectPropertyAxioms) {
			String propertyIdString = transitiveObjectPropertyAxiom.getProperty().getNamedProperty().getIRI().getShortForm();
			transitiveProperties.add(Long.parseLong(propertyIdString));
		}

		return transitiveProperties;
	}

	private OWLClassExpression getOnlyValueOrIntersection(Set<OWLClassExpression> terms) {
		return terms.size() == 1 ? terms.iterator().next() : factory.getOWLObjectIntersectionOf(terms);
	}

	private OWLObjectSomeValuesFrom getOwlObjectSomeValuesFromGroup(OWLClassExpression owlObjectSomeValuesFrom) {
		return getOwlObjectSomeValuesWithPrefix(SNOMED_ROLE_GROUP, owlObjectSomeValuesFrom);
	}

	private OWLObjectSomeValuesFrom getOwlObjectSomeValuesWithPrefix(String prefix, OWLClassExpression owlObjectSomeValuesFrom) {
		return factory.getOWLObjectSomeValuesFrom(factory.getOWLObjectProperty(prefix, prefixManager), owlObjectSomeValuesFrom);
	}

	private OWLObjectSomeValuesFrom getOwlObjectSomeValuesFrom(long typeId, long destinationId) {
		return factory.getOWLObjectSomeValuesFrom(getOwlObjectProperty(typeId), getOwlClass(destinationId));
	}

	private OWLObjectProperty getOwlObjectProperty(long typeId) {
		return factory.getOWLObjectProperty(SNOMED_PREFIX + typeId, prefixManager);
	}

	private OWLClass getOwlClass(Long conceptId) {
		return factory.getOWLClass(SNOMED_PREFIX + conceptId, prefixManager);
	}

	public DefaultPrefixManager getPrefixManager() {
		return prefixManager;
	}
}
