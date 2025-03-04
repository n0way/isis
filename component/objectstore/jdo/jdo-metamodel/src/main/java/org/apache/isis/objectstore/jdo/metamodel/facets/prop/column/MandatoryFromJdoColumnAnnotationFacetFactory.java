/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.isis.objectstore.jdo.metamodel.facets.prop.column;

import java.util.List;

import javax.jdo.annotations.Column;

import com.google.common.base.Strings;

import org.apache.isis.core.commons.config.IsisConfiguration;
import org.apache.isis.core.metamodel.facetapi.FacetUtil;
import org.apache.isis.core.metamodel.facetapi.FeatureType;
import org.apache.isis.core.metamodel.facetapi.MetaModelValidatorRefiner;
import org.apache.isis.core.metamodel.facets.Annotations;
import org.apache.isis.core.metamodel.facets.FacetFactoryAbstract;
import org.apache.isis.core.metamodel.facets.FacetedMethod;
import org.apache.isis.core.metamodel.facets.mandatory.MandatoryFacet;
import org.apache.isis.core.metamodel.facets.mandatory.MandatoryFacetDefault;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.spec.feature.Contributed;
import org.apache.isis.core.metamodel.spec.feature.ObjectAssociation;
import org.apache.isis.core.metamodel.specloader.validator.MetaModelValidatorComposite;
import org.apache.isis.core.metamodel.specloader.validator.MetaModelValidatorVisiting;
import org.apache.isis.core.metamodel.specloader.validator.MetaModelValidatorVisiting.Visitor;
import org.apache.isis.core.metamodel.specloader.validator.ValidationFailures;
import org.apache.isis.core.progmodel.facets.properties.mandatory.annotation.MandatoryFacetExplicitForProperty;
import org.apache.isis.objectstore.jdo.metamodel.facets.prop.primarykey.OptionalFacetDerivedFromJdoPrimaryKeyAnnotation;


public class MandatoryFromJdoColumnAnnotationFacetFactory extends FacetFactoryAbstract implements MetaModelValidatorRefiner {

    public MandatoryFromJdoColumnAnnotationFacetFactory() {
        super(FeatureType.PROPERTIES_ONLY);
    }

    @Override
    public void process(final ProcessMethodContext processMethodContext) {
        final Column annotation = Annotations.getAnnotation(processMethodContext.getMethod(), Column.class);

        final FacetedMethod holder = processMethodContext.getFacetHolder();
        
        final MandatoryFacet existingFacet = holder.getFacet(MandatoryFacet.class);
        if(existingFacet != null) {
            
            if (existingFacet instanceof OptionalFacetDerivedFromJdoPrimaryKeyAnnotation) {
                // do not replace this facet; 
                // we must keep an optional facet here for different reasons
                return;
            }
            if (existingFacet instanceof MandatoryFacetExplicitForProperty) {
                // do not replace this facet; 
                // an explicit @Mandatory annotation cannot be overridden by @Column annotation
                return;
            }
        }
        
        boolean required = whetherRequired(processMethodContext, annotation);
        MandatoryFacet facet = annotation != null 
                ? new MandatoryFacetDerivedFromJdoColumn(holder, required) 
                : new MandatoryFacetInferredFromAbsenceOfJdoColumn(holder, required);
                
        
        // as a side-effect, will chain any existing facets.
        // we'll exploit this fact for meta-model validation (see #refineMetaModelValidator(), below)
        FacetUtil.addFacet(facet);
        
        // however, if a @Column was explicitly provided, and the underlying facet 
        // was the simple MandatoryFacetDefault (from an absence of @Optional or @Mandatory),
        // then don't chain, simply replace.
        if(facet instanceof MandatoryFacetDerivedFromJdoColumn && facet.getUnderlyingFacet() instanceof MandatoryFacetDefault) {
            facet.setUnderlyingFacet(null);
        }
    }

    private static boolean whetherRequired(final ProcessMethodContext processMethodContext, final Column annotation) {

        final String allowsNull = annotation != null ? annotation.allowsNull() : null;
        
        if(Strings.isNullOrEmpty(allowsNull)) {
            final Class<?> returnType = processMethodContext.getMethod().getReturnType();
            // per JDO spec
            return returnType != null && returnType.isPrimitive();
        } else {
            return "false".equalsIgnoreCase(allowsNull.trim());
        }
    }

    @Override
    public void refineMetaModelValidator(MetaModelValidatorComposite metaModelValidator, IsisConfiguration configuration) {
        metaModelValidator.add(new MetaModelValidatorVisiting(newValidatorVisitor()));
    }

    private Visitor newValidatorVisitor() {
        return new MetaModelValidatorVisiting.Visitor() {

            @Override
            public boolean visit(ObjectSpecification objectSpec, ValidationFailures validationFailures) {
                validate(objectSpec, validationFailures);
                return true;
            }

            private void validate(ObjectSpecification objectSpec, ValidationFailures validationFailures) {
                
                List<ObjectAssociation> associations = objectSpec.getAssociations(Contributed.EXCLUDED, ObjectAssociation.Filters.PROPERTIES);
                for (ObjectAssociation association : associations) {
                    
                    
                    MandatoryFacet facet = association.getFacet(MandatoryFacet.class);

                    MandatoryFacet underlying = (MandatoryFacet) facet.getUnderlyingFacet();
                    if(underlying == null) {
                        continue;
                    }

                    if(facet instanceof MandatoryFacetDerivedFromJdoColumn) {

                        if(association.isNotPersisted()) {
                            validationFailures.add("%s: @javax.jdo.annotations.Column found on non-persisted property; please remove)", association.getIdentifier().toClassAndNameIdentityString());
                            continue;
                        }

                        if(underlying.isInvertedSemantics() == facet.isInvertedSemantics()) {
                            continue;
                        }
                        
                        if(underlying.isInvertedSemantics()) {
                            // ie @Optional
                            validationFailures.add("%s: incompatible usage of Isis' @Optional annotation and @javax.jdo.annotations.Column; use just @javax.jdo.annotations.Column(allowNulls=\"...\")", association.getIdentifier().toClassAndNameIdentityString());
                        } else {
                            validationFailures.add("%s: incompatible Isis' default of required/optional properties vs JDO; add @javax.jdo.annotations.Column(allowNulls=\"...\")", association.getIdentifier().toClassAndNameIdentityString());
                        }
                    }
                    
                    if(facet instanceof MandatoryFacetInferredFromAbsenceOfJdoColumn) {
                        
                        if(association.isNotPersisted()) {
                            // nothing to do.
                            continue;
                        }

                        if(underlying.isInvertedSemantics() == facet.isInvertedSemantics()) {
                            continue;
                        }
                        if(underlying.isInvertedSemantics()) {
                            // ie @Optional
                            validationFailures.add("%s: incompatible usage of Isis' @Optional annotation and @javax.jdo.annotations.Column; use just @javax.jdo.annotations.Column(allowNulls=\"...\")", association.getIdentifier().toClassAndNameIdentityString());
                        } else {
                            validationFailures.add("%s: incompatible default handling of required/optional properties between Isis and JDO; add @javax.jdo.annotations.Column(allowsNull=\"...\")", association.getIdentifier().toClassAndNameIdentityString());
                        }
                    }
                }
            }
        };
    }
    
}
