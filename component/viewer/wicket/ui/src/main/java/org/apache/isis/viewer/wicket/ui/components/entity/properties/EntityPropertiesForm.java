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
package org.apache.isis.viewer.wicket.ui.components.entity.properties;

import java.util.List;
import java.util.Map;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Session;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.attributes.IAjaxCallListener;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.FormComponent;
import org.apache.wicket.markup.html.panel.ComponentFeedbackPanel;
import org.apache.wicket.markup.html.panel.FeedbackPanel;
import org.apache.wicket.markup.repeater.RepeatingView;
import org.apache.wicket.model.Model;
import org.apache.wicket.util.visit.IVisit;
import org.apache.wicket.util.visit.IVisitor;

import org.apache.isis.applib.annotation.MemberGroupLayout.ColumnSpans;
import org.apache.isis.applib.annotation.Where;
import org.apache.isis.applib.filter.Filter;
import org.apache.isis.applib.filter.Filters;
import org.apache.isis.applib.services.exceprecog.ExceptionRecognizer;
import org.apache.isis.applib.services.exceprecog.ExceptionRecognizerComposite;
import org.apache.isis.core.commons.authentication.MessageBroker;
import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.adapter.mgr.AdapterManager.ConcurrencyChecking;
import org.apache.isis.core.metamodel.adapter.version.ConcurrencyException;
import org.apache.isis.core.metamodel.facets.object.membergroups.MemberGroupLayoutFacet;
import org.apache.isis.core.metamodel.runtimecontext.ServicesInjector;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.spec.ObjectSpecifications;
import org.apache.isis.core.metamodel.spec.ObjectSpecifications.MemberGroupLayoutHint;
import org.apache.isis.core.metamodel.spec.feature.Contributed;
import org.apache.isis.core.metamodel.spec.feature.ObjectAssociation;
import org.apache.isis.core.metamodel.spec.feature.OneToOneAssociation;
import org.apache.isis.core.runtime.memento.Memento;
import org.apache.isis.core.runtime.system.context.IsisContext;
import org.apache.isis.core.runtime.system.transaction.IsisTransactionManager;
import org.apache.isis.viewer.wicket.model.mementos.PropertyMemento;
import org.apache.isis.viewer.wicket.model.models.EntityModel;
import org.apache.isis.viewer.wicket.model.models.ScalarModel;
import org.apache.isis.viewer.wicket.ui.ComponentType;
import org.apache.isis.viewer.wicket.ui.components.widgets.formcomponent.CancelHintRequired;
import org.apache.isis.viewer.wicket.ui.errors.JGrowlBehaviour;
import org.apache.isis.viewer.wicket.ui.pages.entity.EntityPage;
import org.apache.isis.viewer.wicket.ui.panels.ButtonWithPreValidateHook;
import org.apache.isis.viewer.wicket.ui.panels.FormAbstract;
import org.apache.isis.viewer.wicket.ui.util.Components;
import org.apache.isis.viewer.wicket.ui.util.CssClassAppender;

class EntityPropertiesForm extends FormAbstract<ObjectAdapter> {

    private static final long serialVersionUID = 1L;

    private static final String ID_MEMBER_GROUP = "memberGroup";
    private static final String ID_MEMBER_GROUP_NAME = "memberGroupName";

    private static final String ID_LEFT_COLUMN = "leftColumn";
    private static final String ID_MIDDLE_COLUMN = "middleColumn";
    private static final String ID_RIGHT_COLUMN = "rightColumn";
    
    private static final String ID_ENTITY_COLLECTIONS = "entityCollections";
    private static final String ID_ENTITY_COLLECTIONS_OVERFLOW = "entityCollectionsOverflow";
    
    private static final String ID_PROPERTIES = "properties";
    private static final String ID_PROPERTY = "property";
    private static final String ID_EDIT_BUTTON = "edit";
    private static final String ID_OK_BUTTON = "ok";
    private static final String ID_CANCEL_BUTTON = "cancel";
    private static final String ID_FEEDBACK = "feedback";

    private final Component owningPanel;
    private Button editButton;
    private Button okButton;
    private Button cancelButton;
    private FeedbackPanel feedback;
    
    private boolean renderedFirstField;

    public EntityPropertiesForm(final String id, final EntityModel entityModel, final Component owningPanel) {
        super(id, entityModel);
        this.owningPanel = owningPanel; // for repainting

        buildGui();
        
        // add any concurrency exception that might have been propagated into the entity model 
        // as a result of a previous action invocation
        final String concurrencyExceptionIfAny = entityModel.getAndClearConcurrencyExceptionIfAny();
        if(concurrencyExceptionIfAny != null) {
            error(concurrencyExceptionIfAny);
        }
    }

    private void buildGui() {

        final EntityModel entityModel = (EntityModel) getModel();
        final ColumnSpans columnSpans = entityModel.getObject().getSpecification().getFacet(MemberGroupLayoutFacet.class).getColumnSpans();

        renderedFirstField = false;
        
        // left column
        MarkupContainer leftColumn = new WebMarkupContainer(ID_LEFT_COLUMN);
        add(leftColumn);
        
        boolean added = addPropertiesInColumn(leftColumn, MemberGroupLayoutHint.LEFT, columnSpans);
        addButtons(leftColumn);
        addFeedbackGui(leftColumn);
        if(!added) {
            // a bit hacky...
            Components.permanentlyHide(this, editButton.getId(), okButton.getId(), cancelButton.getId(), ID_FEEDBACK);
        }
        
        // middle column
        if(columnSpans.getMiddle() > 0) {
            MarkupContainer middleColumn = new WebMarkupContainer(ID_MIDDLE_COLUMN);
            add(middleColumn);
            addPropertiesInColumn(middleColumn, MemberGroupLayoutHint.MIDDLE, columnSpans);
        } else {
            Components.permanentlyHide(this, ID_MIDDLE_COLUMN);
        }

        // right column
        if(columnSpans.getRight() > 0) {
            MarkupContainer rightColumn = new WebMarkupContainer(ID_RIGHT_COLUMN);
            add(rightColumn);
            addPropertiesInColumn(rightColumn, MemberGroupLayoutHint.RIGHT, columnSpans);
        } else {
            Components.permanentlyHide(this, ID_RIGHT_COLUMN);
        }

        // collections
        if(columnSpans.getCollections() > 0) {
            final String idCollectionsToShow;
            final String idCollectionsToHide;
            int collectionSpan;
            if (columnSpans.exceedsRow())  {
                idCollectionsToShow = ID_ENTITY_COLLECTIONS_OVERFLOW;
                idCollectionsToHide = ID_ENTITY_COLLECTIONS;
                collectionSpan = 12;
            } else {
                idCollectionsToShow = ID_ENTITY_COLLECTIONS;
                idCollectionsToHide = ID_ENTITY_COLLECTIONS_OVERFLOW;
                collectionSpan = columnSpans.getCollections();
            }

            final Component collectionsColumn = getComponentFactoryRegistry().addOrReplaceComponent(this, idCollectionsToShow, ComponentType.ENTITY_COLLECTIONS, entityModel);
            addClassForSpan(collectionsColumn, collectionSpan);
            
            Components.permanentlyHide(this, idCollectionsToHide);
        } else {
            Components.permanentlyHide(this, ID_ENTITY_COLLECTIONS);
            Components.permanentlyHide(this, ID_ENTITY_COLLECTIONS_OVERFLOW);
        }

    }

    private boolean addPropertiesInColumn(MarkupContainer markupContainer, MemberGroupLayoutHint hint, ColumnSpans columnSpans) {
        final int span = hint.from(columnSpans);
        
        final EntityModel entityModel = (EntityModel) getModel();
        final ObjectAdapter adapter = entityModel.getObject();
        final ObjectSpecification objSpec = adapter.getSpecification();

        final List<ObjectAssociation> associations = visibleProperties(adapter, objSpec, Where.OBJECT_FORMS);

        final RepeatingView memberGroupRv = new RepeatingView(ID_MEMBER_GROUP);
        markupContainer.add(memberGroupRv);

        Map<String, List<ObjectAssociation>> associationsByGroup = ObjectAssociation.Util.groupByMemberOrderName(associations);
        
        final List<String> groupNames = ObjectSpecifications.orderByMemberGroups(objSpec, associationsByGroup.keySet(), hint);
        
        for(String groupName: groupNames) {
            final List<ObjectAssociation> associationsInGroup = associationsByGroup.get(groupName);
            if(associationsInGroup==null) {
                continue;
            }

            final WebMarkupContainer memberGroupRvContainer = new WebMarkupContainer(memberGroupRv.newChildId());
            memberGroupRv.add(memberGroupRvContainer);
            memberGroupRvContainer.add(new Label(ID_MEMBER_GROUP_NAME, groupName));

            final RepeatingView propertyRv = new RepeatingView(ID_PROPERTIES);
            memberGroupRvContainer.add(propertyRv);

            @SuppressWarnings("unused")
            Component component;
            for (final ObjectAssociation association : associationsInGroup) {
                final WebMarkupContainer propertyRvContainer = new WebMarkupContainer(propertyRv.newChildId());
                propertyRv.add(propertyRvContainer);
                addPropertyToForm(entityModel, association, propertyRvContainer);
            }
        }
        
        addClassForSpan(markupContainer, span);
        return !groupNames.isEmpty();
    }

    private void addPropertyToForm(final EntityModel entityModel,
			final ObjectAssociation association,
			final WebMarkupContainer container) {
		final OneToOneAssociation otoa = (OneToOneAssociation) association;
		final PropertyMemento pm = new PropertyMemento(otoa);

		final ScalarModel scalarModel = entityModel.getPropertyModel(pm);
		final Component component = getComponentFactoryRegistry().addOrReplaceComponent(container, ID_PROPERTY, ComponentType.SCALAR_NAME_AND_VALUE, scalarModel);
		
		if(!renderedFirstField) {
		    component.add(new CssClassAppender("first-field"));
		    renderedFirstField = true;
		}
	}

    private List<ObjectAssociation> visibleProperties(final ObjectAdapter adapter, final ObjectSpecification objSpec, Where where) {
        return objSpec.getAssociations(Contributed.INCLUDED, visiblePropertyFilter(adapter, where));
    }

    @SuppressWarnings("unchecked")
    private Filter<ObjectAssociation> visiblePropertyFilter(final ObjectAdapter adapter, Where where) {
        return Filters.and(ObjectAssociation.Filters.PROPERTIES, ObjectAssociation.Filters.dynamicallyVisible(getAuthenticationSession(), adapter, where));
    }

    private void addButtons(MarkupContainer markupContainer) {
        
        editButton = new AjaxButton(ID_EDIT_BUTTON, Model.of("Edit")) {
            private static final long serialVersionUID = 1L;

            @Override
            public void validate() {

                // same logic as in cancelButton; should this be factored out?
                try {
                    getEntityModel().load(ConcurrencyChecking.CHECK);
                } catch(ConcurrencyException ex) {
                    getMessageBroker().addMessage("Object changed by " + ex.getOid().getVersion().getUser() + ", automatically reloading");
                    getEntityModel().load(ConcurrencyChecking.NO_CHECK);
                }
                
                super.validate();
            }
            
            @Override
            public void onSubmit(final AjaxRequestTarget target, final Form<?> form) {
                getEntityModel().resetPropertyModels();
                toEditMode(target);
            }

            @Override
            protected void onError(final AjaxRequestTarget target, final Form<?> form) {
                toEditMode(target);
            }
        
            @Override
            protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
                super.updateAjaxAttributes(attributes);
                attributes.getAjaxCallListeners().add(new org.apache.wicket.ajax.attributes.AjaxCallListener(){

                    private static final long serialVersionUID = 1L;

                    @Override
                    public CharSequence getSuccessHandler(Component component) {
                        return "$('.first-field input').focus();";
                    }
                });
            }
        };
        
        
        markupContainer.add(editButton);

        
        okButton = new ButtonWithPreValidateHook(ID_OK_BUTTON, Model.of("OK")) {
            private static final long serialVersionUID = 1L;


            @Override
            public String preValidate() {
                // attempt to load with concurrency checking, catching recognized exceptions
                try {
                    getEntityModel().load(ConcurrencyChecking.CHECK); // could have also just called #getObject(), since CHECK is the default

                } catch(RuntimeException ex){
                    String recognizedErrorMessage = recognizeException(ex);
                    if(recognizedErrorMessage == null) {
                        throw ex;
                    }

                    // reload
                    getEntityModel().load(ConcurrencyChecking.NO_CHECK);
                    
                    getForm().clearInput();
                    getEntityModel().resetPropertyModels();
                    
                    toViewMode(null);
                    toEditMode(null);
                    
                    return recognizedErrorMessage;
                }
                
                return null;
            }

            @Override
            public void validate() {

                // add in any error message that we might have recognized from above
                EntityPropertiesForm form = EntityPropertiesForm.this;
                String preValidationErrorIfAny = form.getPreValidationErrorIfAny();
                
                if(preValidationErrorIfAny != null) {
                    feedbackOrNotifyAnyRecognizedError(preValidationErrorIfAny, form);
                    // skip validation, because would relate to old values
                } else {
                    // run Wicket's validation
                    super.validate();
                }
            }
            
            @Override
            public void onSubmit() {
                if (getForm().hasError()) {
                    // stay in edit mode
                    return;
                } 
                
                final ObjectAdapter object = getEntityModel().getObject();
                final Memento snapshotToRollbackToIfInvalid = new Memento(object);
                // to perform object-level validation, we must apply the
                // changes first
                // contrast this with ActionPanel (for validating action
                // arguments) where
                // we do the validation prior to the execution of the
                // action
                getEntityModel().apply();
                final String invalidReasonIfAny = getEntityModel().getReasonInvalidIfAny();
                if (invalidReasonIfAny != null) {
                    getForm().error(invalidReasonIfAny);
                    snapshotToRollbackToIfInvalid.recreateObject();
                    return;
                }
                
                try {
                    EntityPropertiesForm.this.getTransactionManager().flushTransaction();
                } catch(RuntimeException ex) {
                    
                    // There's no need to abort the transaction here, as it will have already been done
                    // (in IsisTransactionManager#executeWithinTransaction(...)).

                    String message = recognizeExceptionAndNotify(ex, EntityPropertiesForm.this);
                    if(message == null) {
                        throw ex;
                    }
                    toEditMode(null);
                    return;
                }

                try {
                    getEntityModel().resetPropertyModels();
                } catch(RuntimeException ex) {
                    throw ex;
                }

                toViewMode(null);
                
                final EntityPage entityPage = new EntityPage(EntityPropertiesForm.this.getModelObject(), null);
                
                // "redirect-after-post"
                EntityPropertiesForm.this.setResponsePage(entityPage);
            }

        };
        markupContainer.add(okButton);

        cancelButton = new AjaxButton(ID_CANCEL_BUTTON, Model.of("Cancel")) {
            private static final long serialVersionUID = 1L;
            
            {
                setDefaultFormProcessing(false);
            }

            @Override
            public void validate() {

                // same logic as in editButton; should this be factored out?
                try {
                    getEntityModel().load(ConcurrencyChecking.CHECK);
                } catch(ConcurrencyException ex) {
                    getMessageBroker().addMessage("Object changed by " + ex.getOid().getVersion().getUser() + ", automatically reloading");
                    getEntityModel().load(ConcurrencyChecking.NO_CHECK);
                }
                super.validate();
            }
            
            @Override
            protected void onSubmit(final AjaxRequestTarget target, final Form<?> form) {
                Session.get().getFeedbackMessages().clear();
                getForm().clearInput();
                getForm().visitFormComponentsPostOrder(new IVisitor<FormComponent<?>, Void>() {

                    @Override
                    public void component(FormComponent<?> formComponent, IVisit<Void> visit) {
                        if (formComponent instanceof CancelHintRequired) {
                            final CancelHintRequired cancelHintRequired = (CancelHintRequired) formComponent;
                            cancelHintRequired.onCancel();
                        }
                    }
                });
                
                try {
                    getEntityModel().resetPropertyModels();
                } catch(RuntimeException ex) {
                    throw ex;
                }
                toViewMode(target);
            }

            @Override
            protected void onError(final AjaxRequestTarget target, final Form<?> form) {
                toViewMode(target);
            }
        };

        markupContainer.add(cancelButton);

        okButton.setOutputMarkupPlaceholderTag(true);
        editButton.setOutputMarkupPlaceholderTag(true);
        cancelButton.setOutputMarkupPlaceholderTag(true);
        
        editButton.add(new JGrowlBehaviour());
        cancelButton.add(new JGrowlBehaviour());
    }

    private String recognizeExceptionAndNotify(RuntimeException ex, Component feedbackComponentIfAny) {
        
        // see if the exception is recognized as being a non-serious error
        
        String recognizedErrorMessageIfAny = recognizeException(ex);
        feedbackOrNotifyAnyRecognizedError(recognizedErrorMessageIfAny, feedbackComponentIfAny);

        return recognizedErrorMessageIfAny;
    }

    private void feedbackOrNotifyAnyRecognizedError(String recognizedErrorMessageIfAny, Component feedbackComponentIfAny) {
        if(recognizedErrorMessageIfAny == null) {
            return;
        }
        
        if(feedbackComponentIfAny != null) {
            feedbackComponentIfAny.error(recognizedErrorMessageIfAny);
        }
        getMessageBroker().addWarning(recognizedErrorMessageIfAny);

        // we clear the abort cause because we've handled rendering the exception
        getTransactionManager().getTransaction().clearAbortCause();
    }

    private String recognizeException(RuntimeException ex) {
        
        // REVIEW: this code is similar to stuff in EntityPropertiesForm, perhaps move up to superclass?
        // REVIEW: similar code also in WebRequestCycleForIsis; combine?
        
        final List<ExceptionRecognizer> exceptionRecognizers = getServicesInjector().lookupServices(ExceptionRecognizer.class);
        final String message = new ExceptionRecognizerComposite(exceptionRecognizers).recognize(ex);
        return message;
    }

    private void requestRepaintPanel(final AjaxRequestTarget target) {
        if (target != null) {
            target.add(owningPanel);
            // TODO: is it necessary to add these too?
            target.add(editButton, okButton, cancelButton, feedback);
        }
    }

    private EntityModel getEntityModel() {
        return (EntityModel) getModel();
    }

    void toViewMode(final AjaxRequestTarget target) {
        getEntityModel().toViewMode();
        editButton.setVisible(isAnythingEditable());
        okButton.setVisible(false);
        cancelButton.setVisible(false);
        requestRepaintPanel(target);
    }

    private boolean isAnythingEditable() {
        final EntityModel entityModel = (EntityModel) getModel();
        final ObjectAdapter adapter = entityModel.getObject();

        return !enabledAssociations(adapter, adapter.getSpecification()).isEmpty();
    }
    
    private List<ObjectAssociation> enabledAssociations(final ObjectAdapter adapter, final ObjectSpecification objSpec) {
        return objSpec.getAssociations(Contributed.EXCLUDED, enabledAssociationFilter(adapter));
    }

    @SuppressWarnings("unchecked")
    private Filter<ObjectAssociation> enabledAssociationFilter(final ObjectAdapter adapter) {
        return Filters.and(ObjectAssociation.Filters.PROPERTIES, ObjectAssociation.Filters.enabled(getAuthenticationSession(), adapter, Where.OBJECT_FORMS));
    }

    private void toEditMode(final AjaxRequestTarget target) {
        getEntityModel().toEditMode();
        editButton.setVisible(false);
        okButton.setVisible(true);
        cancelButton.setVisible(true);
        requestRepaintPanel(target);
    }

    private void addFeedbackGui(MarkupContainer markupContainer) {
        feedback = new ComponentFeedbackPanel(ID_FEEDBACK, this);
        feedback.setOutputMarkupPlaceholderTag(true);
        markupContainer.addOrReplace(feedback);
        feedback.setEscapeModelStrings(false);

        final ObjectAdapter adapter = getEntityModel().getObject();
        if (adapter == null) {
            feedback.error("cannot locate object:" + getEntityModel().getObjectAdapterMemento().toString());
        }
    }

    
    private static void addClassForSpan(final Component component, final int numGridCols) {
        component.add(new CssClassAppender("span"+numGridCols));
    }

    ///////////////////////////////////////////////////////
    // Dependencies (from context)
    ///////////////////////////////////////////////////////
    
    protected IsisTransactionManager getTransactionManager() {
        return IsisContext.getTransactionManager();
    }

    protected ServicesInjector getServicesInjector() {
        return IsisContext.getPersistenceSession().getServicesInjector();
    }

    protected MessageBroker getMessageBroker() {
        return getAuthenticationSession().getMessageBroker();
    }

}