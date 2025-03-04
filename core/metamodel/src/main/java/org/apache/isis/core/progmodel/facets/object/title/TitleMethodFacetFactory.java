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

package org.apache.isis.core.progmodel.facets.object.title;

import java.lang.reflect.Method;

import org.apache.isis.core.commons.lang.ClassExtensions;
import org.apache.isis.core.metamodel.facetapi.FacetHolder;
import org.apache.isis.core.metamodel.facetapi.FacetUtil;
import org.apache.isis.core.metamodel.facetapi.FeatureType;
import org.apache.isis.core.metamodel.facets.object.title.TitleFacet;
import org.apache.isis.core.metamodel.methodutils.MethodScope;
import org.apache.isis.core.progmodel.facets.MethodFinderUtils;
import org.apache.isis.core.progmodel.facets.MethodPrefixBasedFacetFactoryAbstract;
import org.apache.isis.core.progmodel.facets.fallback.FallbackFacetFactory;

public class TitleMethodFacetFactory extends MethodPrefixBasedFacetFactoryAbstract {

    private static final String TO_STRING = "toString";
    private static final String TITLE = "title";

    private static final String[] PREFIXES = { TO_STRING, TITLE, };

    public TitleMethodFacetFactory() {
        super(FeatureType.OBJECTS_ONLY, OrphanValidation.VALIDATE, PREFIXES);
    }

    /**
     * If no title or toString can be used then will use Facets provided by
     * {@link FallbackFacetFactory} instead.
     */
    @Override
    public void process(final ProcessClassContext processClassContext) {
        final Class<?> cls = processClassContext.getCls();
        final FacetHolder facetHolder = processClassContext.getFacetHolder();

        // may have a facet by virtue of @Title, say.
        final TitleFacet existingTitleFacet = facetHolder.getFacet(TitleFacet.class);
        if(existingTitleFacet != null && !existingTitleFacet.isNoop()) {
            return;
        }
        
        Method method = MethodFinderUtils.findMethod(cls, MethodScope.OBJECT, TITLE, String.class, null);
        if (method != null) {
            processClassContext.removeMethod(method);
            FacetUtil.addFacet(new TitleFacetViaTitleMethod(method, facetHolder));
            return;
        }
        

        try {
            method = MethodFinderUtils.findMethod(cls, MethodScope.OBJECT, TO_STRING, String.class, null);
            if (method == null) {
                return;
            }
            if (ClassExtensions.isJavaClass(method.getDeclaringClass())) {
                return;
            }
            processClassContext.removeMethod(method);
            FacetUtil.addFacet(new TitleFacetViaToStringMethod(method, facetHolder));

        } catch (final Exception e) {
            return;
        }
    }
}
