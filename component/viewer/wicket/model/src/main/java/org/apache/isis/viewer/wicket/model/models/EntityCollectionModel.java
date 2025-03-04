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

package org.apache.isis.viewer.wicket.model.models;

import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.apache.isis.core.commons.factory.InstanceUtil;
import org.apache.isis.core.commons.lang.ClassUtil;
import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.adapter.mgr.AdapterManager;
import org.apache.isis.core.metamodel.adapter.mgr.AdapterManager.ConcurrencyChecking;
import org.apache.isis.core.metamodel.facets.collections.sortedby.SortedByFacet;
import org.apache.isis.core.metamodel.facets.object.paged.PagedFacet;
import org.apache.isis.core.metamodel.facets.object.plural.PluralFacet;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.spec.feature.OneToManyAssociation;
import org.apache.isis.core.runtime.system.context.IsisContext;
import org.apache.isis.viewer.wicket.model.links.LinkAndLabel;
import org.apache.isis.viewer.wicket.model.links.LinksProvider;
import org.apache.isis.viewer.wicket.model.mementos.CollectionMemento;
import org.apache.isis.viewer.wicket.model.mementos.ObjectAdapterMemento;
import org.apache.isis.viewer.wicket.model.util.MementoFunctions;
import org.apache.isis.viewer.wicket.model.util.ObjectAdapterFunctions;

/**
 * Model representing a collection of entities, either {@link Type#STANDALONE
 * standalone} (eg result of invoking an action) or {@link Type#PARENTED
 * parented} (contents of the collection of an entity).
 * 
 * <p>
 * So that the model is {@link Serializable}, the {@link ObjectAdapter}s within
 * the collection are stored as {@link ObjectAdapterMemento}s.
 */
public class EntityCollectionModel extends ModelAbstract<List<ObjectAdapter>> implements LinksProvider {

    private static final long serialVersionUID = 1L;

    private static final int PAGE_SIZE_DEFAULT_FOR_PARENTED = 12;
    private static final int PAGE_SIZE_DEFAULT_FOR_STANDALONE = 25;

    public enum Type {
        /**
         * A simple list of object mementos, eg the result of invoking an action
         * 
         * <p>
         * This deals with both persisted and transient objects.
         */
        STANDALONE {
            @Override
            List<ObjectAdapter> load(final EntityCollectionModel entityCollectionModel) {
                return Lists.transform(entityCollectionModel.mementoList, ObjectAdapterFunctions.fromMemento());
            }

            @Override
            void setObject(EntityCollectionModel entityCollectionModel, List<ObjectAdapter> list) {
                entityCollectionModel.mementoList = Lists.newArrayList(Lists.transform(list, ObjectAdapterFunctions.toMemento()));
            }

            @Override
            public String getName(EntityCollectionModel model) {
                PluralFacet facet = model.getTypeOfSpecification().getFacet(PluralFacet.class);
                return facet.value();
            }

        },
        /**
         * A collection of an entity (eg Order/OrderDetail).
         */
        PARENTED {
            @Override
            List<ObjectAdapter> load(final EntityCollectionModel entityCollectionModel) {
                final ObjectAdapter adapter = entityCollectionModel.parentObjectAdapterMemento.getObjectAdapter(ConcurrencyChecking.NO_CHECK);
                final OneToManyAssociation collection = entityCollectionModel.collectionMemento.getCollection();
                final ObjectAdapter collectionAsAdapter = collection.get(adapter);

                final List<Object> objectList = asIterable(collectionAsAdapter);

                final Class<? extends Comparator<?>> sortedBy = entityCollectionModel.sortedBy;
                if(sortedBy != null) {
                    @SuppressWarnings("unchecked")
                    final Comparator<Object> comparator = (Comparator<Object>) InstanceUtil.createInstance(sortedBy);
                    Collections.sort(objectList, comparator);
                }

                final Iterable<ObjectAdapter> adapterIterable = Iterables.transform(objectList, ObjectAdapterFunctions.fromPojo(getAdapterManagerStatic()));
                final List<ObjectAdapter> adapterList = Lists.newArrayList(adapterIterable);

                return adapterList;
            }

            @SuppressWarnings("unchecked")
            private List<Object> asIterable(final ObjectAdapter collectionAsAdapter) {
                final Iterable<Object> objects = (Iterable<Object>) collectionAsAdapter.getObject();
                return Lists.newArrayList(objects);
            }

            @Override
            void setObject(EntityCollectionModel entityCollectionModel, List<ObjectAdapter> list) {
                // no-op
                throw new UnsupportedOperationException();
            }

            @Override
            public String getName(EntityCollectionModel model) {
                return model.getCollectionMemento().getName();
            }
        };

        abstract List<ObjectAdapter> load(EntityCollectionModel entityCollectionModel);

        abstract void setObject(EntityCollectionModel entityCollectionModel, List<ObjectAdapter> list);

        public abstract String getName(EntityCollectionModel entityCollectionModel);
    }

    /**
     * Factory.
     */
    public static EntityCollectionModel createStandalone(final ObjectAdapter collectionAsAdapter) {
        final Iterable<Object> iterable = EntityCollectionModel.asIterable(collectionAsAdapter);

        final Iterable<ObjectAdapterMemento> oidIterable = Iterables.transform(iterable, MementoFunctions.fromPojo(getAdapterManagerStatic()));
        final List<ObjectAdapterMemento> mementoList = Lists.newArrayList(oidIterable);

        final ObjectSpecification elementSpec = collectionAsAdapter.getElementSpecification();
        final Class<?> elementType = elementSpec.getCorrespondingClass();
        int pageSize = pageSize(elementSpec.getFacet(PagedFacet.class), PAGE_SIZE_DEFAULT_FOR_STANDALONE);
        
        return new EntityCollectionModel(elementType, mementoList, pageSize);
    }

    /**
     * Factory.
     */
    public static EntityCollectionModel createParented(final EntityModel model, final OneToManyAssociation collection) {
        return new EntityCollectionModel(model, collection);
    }

    /**
     * Factory.
     */
    public static EntityCollectionModel createParented(final ObjectAdapter adapter, final OneToManyAssociation collection) {
        return new EntityCollectionModel(adapter, collection);
    }

    private final Type type;

    private final Class<?> typeOf;
    private transient ObjectSpecification typeOfSpec;

    /**
     * Populated only if {@link Type#STANDALONE}.
     */
    private List<ObjectAdapterMemento> mementoList;

    /**
     * Populated only if {@link Type#STANDALONE}.
     */
    private List<ObjectAdapterMemento> toggledMementosList;

    /**
     * Populated only if {@link Type#PARENTED}.
     */
    private ObjectAdapterMemento parentObjectAdapterMemento;

    /**
     * Populated only if {@link Type#PARENTED}.
     */
    private CollectionMemento collectionMemento;

    private final int pageSize;

    /**
     * Additional links to render (if any)
     */
    private List<LinkAndLabel> entityActions = Lists.newArrayList();

    /**
     * Optionally populated only if {@link Type#PARENTED}.
     */
    private Class<? extends Comparator<?>> sortedBy;

    private EntityCollectionModel(final Class<?> typeOf, final List<ObjectAdapterMemento> mementoList, final int pageSize) {
        this.type = Type.STANDALONE;
        this.typeOf = typeOf;
        this.mementoList = mementoList;
        this.pageSize = pageSize;
        this.toggledMementosList = Lists.newArrayList();
    }

    private EntityCollectionModel(final ObjectAdapter adapter, final OneToManyAssociation collection) {
        this(ObjectAdapterMemento.createOrNull(adapter), collection);
    }

    private EntityCollectionModel(final EntityModel model, final OneToManyAssociation collection) {
        this(model.getObjectAdapterMemento(), collection);
    }

    private EntityCollectionModel(final ObjectAdapterMemento parentAdapterMemento, final OneToManyAssociation collection) {
        this.type = Type.PARENTED;
        this.typeOf = forName(collection.getSpecification());
        this.parentObjectAdapterMemento = parentAdapterMemento;
        this.collectionMemento = new CollectionMemento(collection);
        this.pageSize = pageSize(collection.getFacet(PagedFacet.class), PAGE_SIZE_DEFAULT_FOR_PARENTED);
        final SortedByFacet sortedByFacet = collection.getFacet(SortedByFacet.class);
        this.sortedBy = sortedByFacet != null?sortedByFacet.value(): null;
    }
    
    private static Class<?> forName(final ObjectSpecification objectSpec) {
        final String fullName = objectSpec.getFullIdentifier();
        return ClassUtil.forName(fullName);
    }


    private static int pageSize(final PagedFacet pagedFacet, final int defaultPageSize) {
        return pagedFacet != null ? pagedFacet.value(): defaultPageSize;
    }

    public boolean isParented() {
        return type == Type.PARENTED;
    }

    public boolean isStandalone() {
        return type == Type.STANDALONE;
    }

    public int getPageSize() {
        return pageSize;
    }
    
    /**
     * The name of the collection (if has an entity, ie, if
     * {@link #isParented() is parented}.)
     * 
     * <p>
     * If {@link #isStandalone()}, returns the {@link PluralFacet} of the {@link #getTypeOfSpecification() specification}
     * (eg 'Customers').
     */
    public String getName() {
        return type.getName(this);
    }

    @Override
    protected List<ObjectAdapter> load() {
        return type.load(this);
    }

    public ObjectSpecification getTypeOfSpecification() {
        if (typeOfSpec == null) {
            typeOfSpec = IsisContext.getSpecificationLoader().loadSpecification(typeOf);
        }
        return typeOfSpec;
    }

    @Override
    public void setObject(List<ObjectAdapter> list) {
        super.setObject(list);
        type.setObject(this, list);
    }
    
    /**
     * Populated only if {@link Type#PARENTED}.
     */
    public ObjectAdapterMemento getParentObjectAdapterMemento() {
        return parentObjectAdapterMemento;
    }

    /**
     * Populated only if {@link Type#PARENTED}.
     */
    public CollectionMemento getCollectionMemento() {
        return collectionMemento;
    }

    @SuppressWarnings("unchecked")
    public static Iterable<Object> asIterable(final ObjectAdapter resultAdapter) {
        return (Iterable<Object>) resultAdapter.getObject();
    }

    
    public void toggleSelectionOn(ObjectAdapter selectedAdapter) {
        ObjectAdapterMemento selectedAsMemento = ObjectAdapterMemento.createOrNull(selectedAdapter);
        
        // try to remove; if couldn't, then mustn't have been in there, in which case add.
        boolean removed = toggledMementosList.remove(selectedAsMemento);
        if(!removed) {
            toggledMementosList.add(selectedAsMemento);
        }
    }
    
    public List<ObjectAdapterMemento> getToggleMementosList() {
        return Collections.unmodifiableList(this.toggledMementosList);
    }

    public void clearToggleMementosList() {
        this.toggledMementosList.clear();
    }

    public void addEntityActions(List<LinkAndLabel> entityActions) {
        this.entityActions.addAll(entityActions);
    }

    @Override
    public List<LinkAndLabel> getLinks() {
        return Collections.unmodifiableList(entityActions);
    }

    public EntityCollectionModel asDummy() {
        return new EntityCollectionModel(typeOf, Collections.<ObjectAdapterMemento>emptyList(), pageSize);
    }
    
    // //////////////////////////////////////

    private static AdapterManager getAdapterManagerStatic() {
        return IsisContext.getPersistenceSession().getAdapterManager();
    }

}
