/*
 * Copyright 2004,2005 The Apache Software Foundation.
 * Copyright 2006 International Business Machines Corp.
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


package org.apache.axis2.jaxws.description.impl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.wsdl.Binding;
import javax.wsdl.Definition;
import javax.wsdl.PortType;
import javax.xml.namespace.QName;

import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.jaxws.ExceptionFactory;
import org.apache.axis2.jaxws.description.EndpointDescription;
import org.apache.axis2.jaxws.description.EndpointDescriptionWSDL;
import org.apache.axis2.jaxws.description.EndpointInterfaceDescription;
import org.apache.axis2.jaxws.description.EndpointInterfaceDescriptionJava;
import org.apache.axis2.jaxws.description.EndpointInterfaceDescriptionWSDL;
import org.apache.axis2.jaxws.description.OperationDescription;
import org.apache.axis2.jaxws.description.ServiceDescriptionWSDL;
import org.apache.axis2.jaxws.description.builder.DescriptionBuilderComposite;
import org.apache.axis2.jaxws.description.builder.MDQConstants;
import org.apache.axis2.jaxws.description.builder.MethodDescriptionComposite;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
//import org.apache.log4j.BasicConfigurator;

/**
 * @see ../EndpointInterfaceDescription
 *
 */
class EndpointInterfaceDescriptionImpl 
implements EndpointInterfaceDescription, EndpointInterfaceDescriptionJava, EndpointInterfaceDescriptionWSDL {
    private EndpointDescriptionImpl parentEndpointDescription;
    private ArrayList<OperationDescription> operationDescriptions = new ArrayList<OperationDescription>();
    // This may be an actual Service Endpoint Interface -OR- it may be a service implementation class that did not 
    // specify an @WebService.endpointInterface.
    private Class seiClass;
    private DescriptionBuilderComposite dbc;
    
    //Logging setup
    private static final Log log = LogFactory.getLog(EndpointInterfaceDescriptionImpl.class);
    
    // ===========================================
    // ANNOTATION related information
    // ===========================================
    
    // ANNOTATION: @WebService
    private WebService          webServiceAnnotation;
    private String              webServiceTargetNamespace;
    
    
    // ANNOTATION: @SOAPBinding
    // Note this is the Type-level annotation.  See OperationDescription for the Method-level annotation
    private SOAPBinding         soapBindingAnnotation;
    // TODO: Should this be using the jaxws annotation values or should that be wrappered?
    private javax.jws.soap.SOAPBinding.Style            soapBindingStyle;
    // Default value per JSR-181 MR Sec 4.7 "Annotation: javax.jws.soap.SOAPBinding" pg 28
    public static final javax.jws.soap.SOAPBinding.Style SOAPBinding_Style_DEFAULT = javax.jws.soap.SOAPBinding.Style.DOCUMENT;
    private javax.jws.soap.SOAPBinding.Use              soapBindingUse;
    // Default value per JSR-181 MR Sec 4.7 "Annotation: javax.jws.soap.SOAPBinding" pg 28
    public static final javax.jws.soap.SOAPBinding.Use  SOAPBinding_Use_DEFAULT = javax.jws.soap.SOAPBinding.Use.LITERAL;
    private javax.jws.soap.SOAPBinding.ParameterStyle   soapParameterStyle;
    // Default value per JSR-181 MR Sec 4.7 "Annotation: javax.jws.soap.SOAPBinding" pg 28
    public static final javax.jws.soap.SOAPBinding.ParameterStyle SOAPBinding_ParameterStyle_DEFAULT = javax.jws.soap.SOAPBinding.ParameterStyle.WRAPPED;
    
    void addOperation(OperationDescription operation) {
        operationDescriptions.add(operation);
    }
    
    EndpointInterfaceDescriptionImpl(Class sei, EndpointDescriptionImpl parent) {
        seiClass = sei;
        
        // Per JSR-181 all methods on the SEI are mapped to operations regardless
        // of whether they include an @WebMethod annotation.  That annotation may
        // be present to customize the mapping, but is not required (p14)
        // TODO:  Testcases that do and do not include @WebMethod anno
        for (Method method:getSEIMethods(seiClass)) {
            OperationDescription operation = new OperationDescriptionImpl(method, this);
            addOperation(operation);
        }
        
        parentEndpointDescription = parent;
    }
    
    /**
     * Build from AxisService
     * @param parent
     */
    EndpointInterfaceDescriptionImpl(EndpointDescriptionImpl parent) {
        parentEndpointDescription = parent;
        AxisService axisService = parentEndpointDescription.getAxisService();
        if (axisService != null) {
            ArrayList publishedOperations = axisService.getPublishedOperations();
            Iterator operationsIterator = publishedOperations.iterator();
            while (operationsIterator.hasNext()) {
                AxisOperation axisOperation = (AxisOperation) operationsIterator.next();
                addOperation(new OperationDescriptionImpl(axisOperation, this));
            }
        }
    }

    /**
     * Build an EndpointInterfaceDescription from a DescriptionBuilderComposite
     * @param dbc
     * @param isClass
     * @param parent
     */
    EndpointInterfaceDescriptionImpl(   DescriptionBuilderComposite dbc, 
                                    boolean isClass,
                                    EndpointDescriptionImpl parent){
        
        parentEndpointDescription = parent;
        this.dbc = dbc;
        
//        BasicConfigurator.configure();
        
        //TODO: yikes! ...too much redirection, consider setting this in higher level
        getEndpointDescription().getAxisService().setName(getEndpointDescriptionImpl().getServiceQName().getLocalPart());
        getEndpointDescription().getAxisService().setTargetNamespace(getEndpointDescriptionImpl().getTargetNamespace());
		        
        //TODO: Determine if the isClass parameter is really necessary
        
        // Per JSR-181 all methods on the SEI are mapped to operations regardless
        // of whether they include an @WebMethod annotation.  That annotation may
        // be present to customize the mapping, but is not required (p14)
        
        // TODO:  Testcases that do and do not include @WebMethod anno
        
        //We are processing the SEI composite
        //For every MethodDescriptionComposite in this list, call OperationDescription 
        //constructor for it, then add this operation
        
        //Retrieve the relevent method composites for this dbc (and those in the superclass chain)
        Iterator<MethodDescriptionComposite> iter = retrieveReleventMethods(dbc);
        
        if (log.isDebugEnabled())
            log.debug("EndpointInterfaceDescriptionImpl: Finished retrieving methods");
        MethodDescriptionComposite mdc = null;
        
        while (iter.hasNext()) {
            mdc = iter.next();
            
            //TODO: Verify that this classname is truly always the wrapper class
            mdc.setDeclaringClass(dbc.getClassName());
            OperationDescription operation = new OperationDescriptionImpl(mdc, this);
    
            //TODO: Do we need to worry about a null AxisOperation at this level?
            
            //Add this AxisOperation to the AxisService
            getEndpointDescription().getAxisService().addOperation(operation.getAxisOperation());
        
            if (log.isDebugEnabled())
                log.debug("EID: Just added operation= " +operation.getOperationName());
            addOperation(operation);
        
        }
        
        if (log.isDebugEnabled())
            log.debug("EndpointInterfaceDescriptionImpl: Finished Adding operations");
        
        //TODO: Need to process the other annotations that can exist, on the server side
        //      and at the class level.
        //      They are, as follows:       
        //          HandlerChain (181)
        //          SoapBinding (181)
        //          WebServiceRefAnnot (List) (JAXWS)
        //          BindingTypeAnnot (JAXWS Sec. 7.8 -- Used to set either the AS.endpoint, or AS.SoapNSUri)
        //          WebServiceContextAnnot (JAXWS via injection)
        
//        BasicConfigurator.resetConfiguration();
    }

    private static Method[] getSEIMethods(Class sei) {
        // Per JSR-181 all methods on the SEI are mapped to operations regardless
        // of whether they include an @WebMethod annotation.  That annotation may
        // be present to customize the mapping, but is not required (p14)
        Method[] seiMethods = sei.getMethods();
        if (sei != null) {
            for (Method method:seiMethods) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    // JSR-181 says methods must be public (p14)
                    // TODO NLS
                    ExceptionFactory.makeWebServiceException("SEI methods must be public");
                }
                // TODO: other validation per JSR-181
            }
            
        }
        return seiMethods;
    }
    
    /**
     * Update a previously created EndpointInterfaceDescription with information from an
     * annotated SEI.  This should only be necessary when the this was created with WSDL.  
     * In this case, the information from the WSDL is augmented based on the annotated SEI.
     * @param sei
     */
    void updateWithSEI(Class sei) {
        if (seiClass != null && seiClass != sei)
            // TODO: It probably is invalid to try reset the SEI; but this isn't the right error processing
            throw new UnsupportedOperationException("The seiClass is already set; reseting it is not supported");
        else if (seiClass != null && seiClass == sei)
            // We've already done the necessary updates for this SEI
            return;
        else if (sei != null) {
            seiClass = sei;
            // Update (or possibly add) the OperationDescription for each of the methods on the SEI.
            for (Method seiMethod:getSEIMethods(seiClass)) {

                if (getOperation(seiMethod) != null) {
                    // If an OpDesc already exists with this java method set on it, then the OpDesc has already
                    // been updated for this method, so skip it.
                    continue;
                }
                // At this point (for now at least) the operations were created with WSDL previously.
                // If they had been created from an annotated class and no WSDL, then the seiClass would have 
                // already been set so we would have taken other branches in this if test.  (Note this could
                // change once AxisServices can be built from annotations by the ServiceDescription class).
                // Since the operations were created from WSDL, they will not have a java method, which
                // comes from the SEI, set on them yet.
                //
                // Another consideration is that currently Axis2 does not support overloaded WSDL operations.
                // That means there will only be one OperationDesc build from WSDL.  Still another consideration is
                // that the JAXWS async methods which may exist on the SEI will NOT exist in the WSDL.  An example
                // of these methods for the WSDL operation:
                //     String echo(String)
                // optionally generated JAX-WS SEI methods from the tooling; take note of the annotation specifying the 
                // operation name
                //     @WebMethod(operationName="echo" ...)
                //     Response<String> echoStringAsync(String)
                //     @WebMethod(operationName="echo" ...)
                //     Future<?> echoStringAsync(String, AsyncHandler)
                //
                // So given all the above, the code does the following based on the operation QName
                // (which might also be the java method name; see determineOperationQName for details)
                // (1) If an operationDesc does not exist, add it.
                // (2) If an operationDesc does exist but does not have a java method set on it, set it
                // (3) If an operationDesc does exist and has a java method set on it already, add a new one. 
                //
                // TODO: May need to change when Axis2 supports overloaded WSDL operations
                // TODO: May need to change when ServiceDescription can build an AxisService from annotations
                
                // Get the QName for this java method and then update (or add) the appropriate OperationDescription
                // See comments below for imporant notes about the current implementation.
                // NOTE ON OVERLOADED OPERATIONS
                // Axis2 does NOT currently support overloading WSDL operations.
                QName seiOperationQName = OperationDescriptionImpl.determineOperationQName(seiMethod);
                OperationDescription[] updateOpDesc = getOperation(seiOperationQName);
                if (updateOpDesc == null || updateOpDesc.length == 0) {
                    // This operation wasn't defined in the WSDL.  Note that the JAX-WS async methods
                    // which are defined on the SEI are not defined as operations in the WSDL.
                    // Although they usually specific the same OperationName as the WSDL operation, 
                    // there may be cases where they do not.
                    // TODO: Is this path an error path, or can the async methods specify different operation names than the 
                    //       WSDL operation?
                    OperationDescription operation = new OperationDescriptionImpl(seiMethod, this);
                    addOperation(operation);
                }
                else { 
                    // Currently Axis2 does not support overloaded operations.  That means that even if the WSDL
                    // defined overloaded operations, there would still only be a single AxisOperation, and it
                    // would be the last operation encounterd.
                    // HOWEVER the generated JAX-WS async methods (see above) may (will always?) have the same
                    // operation name and so will come down this path; they need to be added.
                    // TODO: When Axis2 starts supporting overloaded operations, then this logic will need to be changed
                    // TODO: Should we verify that these are the async methods before adding them, and treat it as an error otherwise?

                    // Loop through all the opdescs; if one doesn't currently have a java method set, set it
                    // If all have java methods set, then add a new one.  Assume we'll need to add a new one.
                    boolean addOpDesc = true;
                    for (OperationDescription checkOpDesc:updateOpDesc) {
                        if (checkOpDesc.getSEIMethod() == null) {
                            // TODO: Should this be checking (somehow) that the signature matches?  Probably not an issue until overloaded WSDL ops are supported.
                            ((OperationDescriptionImpl) checkOpDesc).setSEIMethod(seiMethod);
                            addOpDesc = false;
                            break;
                        }
                    }
                    if (addOpDesc) {
                        OperationDescription operation = new OperationDescriptionImpl(seiMethod, this);
                        addOperation(operation);
                    }
                }
            }
        }
    }

    /**
     * Return the OperationDescriptions corresponding to a particular Java method name.
     * Note that an array is returned because a method could be overloaded.
     * 
     * @param javaMethodName String representing a Java Method Name
     * @return
     */
    // FIXME: This is confusing; some getOperations use the QName from the WSDL or annotation; this one uses the java method name; rename this signature I think; add on that takes a String but does a QName lookup against the WSDL/Annotation
    public OperationDescription[] getOperationForJavaMethod(String javaMethodName) {
        if (DescriptionUtils.isEmpty(javaMethodName)) {
            return null;
        }
        
        ArrayList<OperationDescription> matchingOperations = new ArrayList<OperationDescription>();
        for (OperationDescription operation: getOperations()) {
            if (javaMethodName.equals(operation.getJavaMethodName())) {
                matchingOperations.add(operation);
            }
        }
        
        if (matchingOperations.size() == 0)
            return null;
        else
            return matchingOperations.toArray(new OperationDescription[0]);
    }
    
    /**
     * Return the OperationDesription (only one) corresponding to the OperationName passed in.
     * @param operationName
     * @return
     */
    public OperationDescription getOperation(String operationName) {
        if (DescriptionUtils.isEmpty(operationName)) {
            return null;
        }
        
        OperationDescription matchingOperation = null;
        for (OperationDescription operation:getOperations()) {
            if (operationName.equals(operation.getOperationName())) {
                matchingOperation = operation;
                break;
            }
        }
        return matchingOperation;
    }
    
    public OperationDescription[] getOperations() {
        return operationDescriptions.toArray(new OperationDescription[0]);
    }
    
    EndpointDescriptionImpl getEndpointDescriptionImpl() {
        return (EndpointDescriptionImpl) parentEndpointDescription;
    }
    public EndpointDescription getEndpointDescription() {
        return parentEndpointDescription;
    }
    
    /**
     * Return an array of Operations given an operation QName.  Note that an array is returned
     * since a WSDL operation may be overloaded per JAX-WS.
     * @param operationQName
     * @return
     */
    public OperationDescription[] getOperation(QName operationQName) {
        OperationDescription[] returnOperations = null;
        if (!DescriptionUtils.isEmpty(operationQName)) {
            ArrayList<OperationDescription> matchingOperations = new ArrayList<OperationDescription>();
            OperationDescription[] allOperations = getOperations();
            for (OperationDescription operation:allOperations) {
                if (operation.getName().equals(operationQName)) {
                    matchingOperations.add(operation);
                }
            }
            // Only return an array if there's anything in it
            if (matchingOperations.size() > 0) {
                returnOperations = matchingOperations.toArray(new OperationDescription[0]);
            }
        }
        return returnOperations;
    }
    
    /**
     * Return an OperationDescription for the corresponding SEI method.  Note that this ONLY works
     * if the OperationDescriptions were created from introspecting an SEI.  If the were created with a WSDL
     * then use the getOperation(QName) method, which can return > 1 operation.
     * @param seiMethod The java.lang.Method from the SEI for which an OperationDescription is wanted
     * @return
     */
    public OperationDescription getOperation(Method seiMethod) {
        OperationDescription returnOperation = null;
        if (seiMethod != null) {
            OperationDescription[] allOperations = getOperations();
            for (OperationDescription operation:allOperations) {
                if (operation.getSEIMethod() != null && operation.getSEIMethod().equals(seiMethod)) {
                    returnOperation = operation;
                }
            }
        }
        return returnOperation;
    }
    
    public Class getSEIClass() {
        return seiClass;
    }
    // Annotation-realted getters
    
    // ========================================
    // SOAP Binding annotation realted methods
    // ========================================
    public SOAPBinding getAnnoSoapBinding(){
        // TODO: Test with sei Null, not null, SOAP Binding annotated, not annotated

        if (soapBindingAnnotation == null) {
            if (dbc != null) {
                soapBindingAnnotation = dbc.getSoapBindingAnnot();
            } else {
                if (seiClass != null) {
                    soapBindingAnnotation = (SOAPBinding) seiClass.getAnnotation(SOAPBinding.class);                
                }
            }
        }
        return soapBindingAnnotation;
    }

    public javax.jws.soap.SOAPBinding.Style getSoapBindingStyle() {
        // REVIEW: Implement WSDL/Anno merge
        return getAnnoSoapBindingStyle();
    }
    
    public javax.jws.soap.SOAPBinding.Style getAnnoSoapBindingStyle() {
        if (soapBindingStyle == null) {
            if (getAnnoSoapBinding() != null && getAnnoSoapBinding().style() != null) {
                soapBindingStyle = getAnnoSoapBinding().style();
            }
            else {
                soapBindingStyle = SOAPBinding_Style_DEFAULT;
            }
        }
        return soapBindingStyle;
    }
    
    public javax.jws.soap.SOAPBinding.Use getSoapBindingUse() {
        // REVIEW: Implement WSDL/Anno merge
        return getAnnoSoapBindingUse();
    }
    
    public javax.jws.soap.SOAPBinding.Use getAnnoSoapBindingUse() {
        if (soapBindingUse == null) {
            if (getAnnoSoapBinding() != null && getAnnoSoapBinding().use() != null) {
                soapBindingUse = getAnnoSoapBinding().use();
            }
            else {
                soapBindingUse = SOAPBinding_Use_DEFAULT;
            }
        }
        return soapBindingUse;
    }
    
    public javax.jws.soap.SOAPBinding.ParameterStyle getSoapBindingParameterStyle(){
        // REVIEW: Implement WSDL/Anno merge
        return getAnnoSoapBindingParameterStyle();
    }
    public javax.jws.soap.SOAPBinding.ParameterStyle getAnnoSoapBindingParameterStyle() {
        if (soapParameterStyle == null) {
            if (getAnnoSoapBinding() != null && getAnnoSoapBinding().parameterStyle() != null) {
                soapParameterStyle = getAnnoSoapBinding().parameterStyle();
            }
            else {
                soapParameterStyle = SOAPBinding_ParameterStyle_DEFAULT;
            }
        }
        return soapParameterStyle;
    }
    
    /*
     * Returns a non-null (possibly empty) list of MethodDescriptionComposites
     */
    Iterator<MethodDescriptionComposite> retrieveReleventMethods(DescriptionBuilderComposite dbc) {
 
        /*
         * Depending on whether this is an implicit SEI or an actual SEI, Gather up and build a 
         * list of MDC's. If this is an actual SEI, then starting with this DBC, build a list of all
         * MDC's that are public methods in the chain of extended classes.
         * If this is an implicit SEI, then starting with this DBC,
         *  1. If a false exclude is found, then take only those that have false excludes
         *  2. Assuming no false excludes, take all public methods that don't have exclude == true
         *  3. For each super class, if 'WebService' present, take all MDC's according to rules 1&2
         *    But, if WebService not present, grab only MDC's that are annotated.
         */
        if (log.isTraceEnabled()) {
            log.trace("retrieveReleventMethods: Enter");
        }
        
        ArrayList<MethodDescriptionComposite> retrieveList = new ArrayList<MethodDescriptionComposite>();

        if (dbc.isInterface()) {
        
            retrieveList = retrieveSEIMethods(dbc);

            //Now gather methods off the chain of superclasses, if any
            DescriptionBuilderComposite tempDBC = dbc;          
            while (!DescriptionUtils.isEmpty(tempDBC.getSuperClassName())) {
            	
                if (DescriptionUtils.javifyClassName(tempDBC.getSuperClassName()).equals(MDQConstants.OBJECT_CLASS_NAME))
                    break;

                DescriptionBuilderComposite superDBC = 
                                    getEndpointDescriptionImpl().getServiceDescriptionImpl().getDBCMap().get(tempDBC.getSuperClassName());
                retrieveList.addAll(retrieveSEIMethods(superDBC));
                tempDBC = superDBC;
            }
                
        } else {
            //this is an implied SEI...rules are more complicated
            
            retrieveList = retrieveImplicitSEIMethods(dbc);
                    
            //Now, continue to build this list with relevent methods in the chain of
            //superclasses. If the logic for processing superclasses is the same as for
            //the original SEI, then we can combine this code with above code. But, its possible
            //the logic is different for superclasses...keeping separate for now.
            DescriptionBuilderComposite tempDBC = dbc;
            
            while (!DescriptionUtils.isEmpty(tempDBC.getSuperClassName())) {
                
                //verify that this superclass name is not
                //      java.lang.object, if so, then we're done processing
                if (DescriptionUtils.javifyClassName(tempDBC.getSuperClassName()).equals(MDQConstants.OBJECT_CLASS_NAME))
                    break;
                
                DescriptionBuilderComposite superDBC = 
                                    getEndpointDescriptionImpl().getServiceDescriptionImpl().getDBCMap().get(tempDBC.getSuperClassName());
                    
                if (log.isTraceEnabled())
                    log.trace("superclass name for this DBC is:" +tempDBC.getSuperClassName());

                //Verify that we can find the SEI in the composite list
                if (superDBC == null){
                    throw ExceptionFactory.makeWebServiceException("EndpointInterfaceDescriptionImpl: cannot find super class that was specified for this class");
                }
                
                if (superDBC.getWebServiceAnnot() != null) {
                    //Now, gather the list of Methods just like we do for the lowest subclass
                    retrieveList.addAll(retrieveImplicitSEIMethods(superDBC));
                } else {
                    //This superclass does not contain a WebService annotation, add only the
                    //methods that are annotated with WebMethod
                    
                    Iterator<MethodDescriptionComposite> iterMethod = dbc.getMethodDescriptionsList().iterator();
                    
                    while (iterMethod.hasNext()) {
                        MethodDescriptionComposite mdc = iterMethod.next();
                        
                        if (!DescriptionUtils.isExcludeTrue(mdc)) {
                            retrieveList.add(mdc);
                        }
                    }                   
                }
                tempDBC = superDBC;
            } //Done with implied SEI's superclasses
                
        }//Done with implied SEI's
        
        return retrieveList.iterator();
    }

    /*
     * This is called when we know that this DBC is an implicit SEI
     */
    private ArrayList<MethodDescriptionComposite> retrieveImplicitSEIMethods(DescriptionBuilderComposite dbc) {
        
        ArrayList<MethodDescriptionComposite> retrieveList = new ArrayList<MethodDescriptionComposite>();

        retrieveList = DescriptionUtils.getMethodsWithFalseExclusions(dbc);
        
        //If this list is empty, then there are no false exclusions, so gather
        //all composites that don't have exclude == true
        //If the list is not empty, then it means we found at least one method with 'exclude==false'
        //so the list should contain only those methods
        if (retrieveList == null || retrieveList.size() == 0) {
            Iterator<MethodDescriptionComposite> iter = null;
            List<MethodDescriptionComposite> mdcList = dbc.getMethodDescriptionsList();

            if (mdcList != null) {
            	iter = dbc.getMethodDescriptionsList().iterator();
                while (iter.hasNext()) {
                    MethodDescriptionComposite mdc = iter.next();
                    
                    if (!DescriptionUtils.isExcludeTrue(mdc)) {
                        retrieveList.add(mdc);
                    }
                }
            }
        }

        return retrieveList;
    }

    private ArrayList<MethodDescriptionComposite> retrieveSEIMethods(DescriptionBuilderComposite dbc) {
        
        //Rules for retrieving Methods on an SEI (or a superclass of an SEI) are simple
        //Just retrieve all methods regardless of WebMethod annotations
        ArrayList<MethodDescriptionComposite> retrieveList = new ArrayList<MethodDescriptionComposite>();
        
        Iterator<MethodDescriptionComposite> iter = null;
        List<MethodDescriptionComposite> mdcList = dbc.getMethodDescriptionsList();
        
        if (mdcList != null) {
        	iter = dbc.getMethodDescriptionsList().iterator();
            while (iter.hasNext()) {
                MethodDescriptionComposite mdc = iter.next();           
                retrieveList.add(mdc);
            }
        }
                
        return retrieveList;
    }

    private Definition getWSDLDefinition() {
        return ((ServiceDescriptionWSDL) getEndpointDescription().getServiceDescription()).getWSDLDefinition();
    }
    public PortType getWSDLPortType() {
        PortType portType = null;
//        EndpointDescriptionWSDL endpointDescWSDL = (EndpointDescriptionWSDL) getEndpointDescription();
//        Binding wsdlBinding = endpointDescWSDL.getWSDLBinding();
//        if (wsdlBinding != null) {
//            portType = wsdlBinding.getPortType();
//        }
        Definition wsdlDefn = getWSDLDefinition();
        if (wsdlDefn != null) {
            String tns = getEndpointDescription().getTargetNamespace();
            String localPart = getEndpointDescription().getName();
            portType = wsdlDefn.getPortType(new QName(tns, localPart));
        }
        return portType;
    }

    
    public String getTargetNamespace() {
        // REVIEW: WSDL/Anno mertge
        return getAnnoWebServiceTargetNamespace();
    }

    public WebService getAnnoWebService() {
        // TODO Auto-generated method stub
        if (webServiceAnnotation == null) {
            if (dbc != null) {
                webServiceAnnotation = dbc.getWebServiceAnnot();
            } else {
                if (seiClass != null) {
                    webServiceAnnotation = (WebService) seiClass.getAnnotation(WebService.class);                
                }
            }
        }
        return webServiceAnnotation;
    }

    public String getAnnoWebServiceTargetNamespace() {
        if (webServiceTargetNamespace == null) {
            if (getAnnoWebService() != null 
                    && !DescriptionUtils.isEmpty(getAnnoWebService().targetNamespace())) {
                webServiceTargetNamespace = getAnnoWebService().targetNamespace();
            }
            else {
                // Default value per JSR-181 MR Sec 4.1 pg 15 defers to "Implementation defined, 
                // as described in JAX-WS 2.0, section 3.2" which is JAX-WS 2.0 Sec 3.2, pg 29.
                // FIXME: Hardcoded protocol for namespace
                if (dbc != null)
                    webServiceTargetNamespace = 
                        DescriptionUtils.makeNamespaceFromPackageName(DescriptionUtils.getJavaPackageName(dbc.getClassName()), "http");
                else
                    webServiceTargetNamespace = 
                        DescriptionUtils.makeNamespaceFromPackageName(DescriptionUtils.getJavaPackageName(seiClass), "http");

            }
        }
        return webServiceTargetNamespace;
    }

}
