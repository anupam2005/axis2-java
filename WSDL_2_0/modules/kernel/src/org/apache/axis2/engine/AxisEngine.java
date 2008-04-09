/*
* Copyright 2004,2005 The Apache Software Foundation.
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


package org.apache.axis2.engine;

import java.util.ArrayList;
import java.util.Iterator;

import org.apache.axiom.soap.SOAP11Constants;
import org.apache.axiom.soap.SOAP12Constants;
import org.apache.axiom.soap.SOAPConstants;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPHeaderBlock;
import org.apache.axis2.AxisFault;
import org.apache.axis2.client.async.Callback;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.engine.Handler.InvocationResponse;
import org.apache.axis2.i18n.Messages;
import org.apache.axis2.transport.TransportSender;
import org.apache.axis2.util.CallbackReceiver;
import org.apache.axis2.util.MessageContextBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * There is one engine for the Server and the Client. the send() and receive()
 * Methods are the basic operations the Sync, Async messageing are build on top.
 */
public class AxisEngine {

    /**
     * Field log
     */
    private static final Log log = LogFactory.getLog(AxisEngine.class);
    private ConfigurationContext engineContext;

    private static boolean RESUMING_EXECUTION = true;
    private static boolean NOT_RESUMING_EXECUTION = false;
    private static boolean IS_INBOUND = true;
    private static boolean IS_OUTBOUND = false;
        
    /**
     * Constructor AxisEngine
     */
    public AxisEngine(ConfigurationContext engineContext) {
        this.engineContext = engineContext;
    }

    private void checkMustUnderstand(MessageContext msgContext) throws AxisFault {
        if (!msgContext.isHeaderPresent()) {
            return;
        }
        SOAPEnvelope envelope = msgContext.getEnvelope();
        if (envelope.getHeader() == null) {
            return;
        }
        Iterator headerBlocks = envelope.getHeader().examineAllHeaderBlocks();
        while (headerBlocks.hasNext()) {
            SOAPHeaderBlock headerBlock = (SOAPHeaderBlock) headerBlocks.next();
            // if this header block has been processed or mustUnderstand isn't
            // turned on then its cool
            if (headerBlock.isProcessed() || !headerBlock.getMustUnderstand()) {
                continue;
            }
            // if this header block is not targetted to me then its not my
            // problem. Currently this code only supports the "next" role; we
            // need to fix this to allow the engine/service to be in one or more
            // additional roles and then to check that any headers targetted for
            // that role too have been dealt with.

            String role = headerBlock.getRole();

            String prefix = envelope.getNamespace().getPrefix();

            if (!msgContext.isSOAP11()) {

                // if must understand and soap 1.2 the Role should be NEXT , if it is null we consider
                // it to be NEXT
                if (prefix == null || "".equals(prefix)) {
                    prefix = SOAPConstants.SOAP_DEFAULT_NAMESPACE_PREFIX;
                }
                if (role != null) {
                    if (!SOAP12Constants.SOAP_ROLE_NEXT.equals(role)) {
                        throw new AxisFault(Messages.getMessage(
                                "mustunderstandfailed",
                                prefix, SOAP12Constants.FAULT_CODE_MUST_UNDERSTAND));
                    }
                } else {
                    throw new AxisFault(Messages.getMessage(
                            "mustunderstandfailed",
                            prefix, SOAP12Constants.FAULT_CODE_MUST_UNDERSTAND));
                }
            } else {

                // if must understand and soap 1.1 the actor should be NEXT , if it is null we considerr
                // it to be NEXT
                if ((role != null) && !SOAP11Constants.SOAP_ACTOR_NEXT.equals(role)) {
                    throw new AxisFault(Messages.getMessage(
                            "mustunderstandfailed",
                            prefix, SOAP12Constants.FAULT_CODE_MUST_UNDERSTAND));
                }
            }
        }
    }

    /**
     * This method is called to handle any error that occurs at inflow or outflow. But if the
     * method is called twice, it implies that sending the error handling has failed, in which case
     * the method logs the error and exists.
     * @deprecated (post 1.1 branch)
     */
    public MessageContext createFaultMessageContext(MessageContext processingContext, Throwable e)
            throws AxisFault {
        return MessageContextBuilder.createFaultMessageContext(processingContext, e);
    }
   
    /**
     * This methods represents the inflow of the Axis, this could be either at the server side or the client side.
     * Here the <code>ExecutionChain</code> is created using the Phases. The Handlers at the each Phases is ordered in
     * deployment time by the deployment module
     *
     * @throws AxisFault
     * @see MessageContext
     * @see Phase
     * @see Handler
     */
    public void receive(MessageContext msgContext) throws AxisFault {
        if(log.isTraceEnabled()){
            log.trace("receive:"+msgContext.getMessageID());
        }
        ConfigurationContext confContext = msgContext.getConfigurationContext();
        ArrayList preCalculatedPhases =
                confContext.getAxisConfiguration().getGlobalInFlow();
        // Set the initial execution chain in the MessageContext to a *copy* of what
        // we got above.  This allows individual message processing to change the chain without
        // affecting later messages.
        msgContext.setExecutionChain((ArrayList) preCalculatedPhases.clone());
        msgContext.setFLOW(MessageContext.IN_FLOW);
        try
        {
          InvocationResponse pi = invoke(msgContext, IS_INBOUND, NOT_RESUMING_EXECUTION);

          if (pi.equals(InvocationResponse.CONTINUE))
          {
            if (msgContext.isServerSide())
            {
              // invoke the Message Receivers
              checkMustUnderstand(msgContext);
              
              MessageReceiver receiver = msgContext.getAxisOperation().getMessageReceiver();

              receiver.receive(msgContext);
            }
            flowComplete(msgContext, true);
          }
          else if (pi.equals(InvocationResponse.SUSPEND))
          {
            return;
          }
          else if (pi.equals(InvocationResponse.ABORT))
          {
            flowComplete(msgContext, true);
            return;
          }
          else
          {
            String errorMsg = "Unrecognized InvocationResponse encountered in AxisEngine.receive()";
            log.error(errorMsg);
            throw new AxisFault(errorMsg);
          }
        }
        catch (AxisFault e)
        {
          flowComplete(msgContext, true);
          throw e;
        }
    }

    /**
     * Take the execution chain from the msgContext , and then take the current Index
     * and invoke all the phases in the arraylist
     * if the msgContext is pauesd then the execution will be breaked
     *
     * @param msgContext
     * @return An InvocationResponse that indicates what 
     *         the next step in the message processing should be.
     * @throws AxisFault
     */
    public InvocationResponse invoke(MessageContext msgContext, boolean inbound, boolean resuming) throws AxisFault {
        if (msgContext.getCurrentHandlerIndex() == -1) {
            msgContext.setCurrentHandlerIndex(0);
        }

        InvocationResponse pi = InvocationResponse.CONTINUE;
        
        while (msgContext.getCurrentHandlerIndex() < msgContext.getExecutionChain().size()) {
            Handler currentHandler = (Handler) msgContext.getExecutionChain().
                    get(msgContext.getCurrentHandlerIndex());
                        
            try
            {
              if (!resuming)
              {
                if (inbound)
                {
                  msgContext.addInboundExecutedPhase(currentHandler);
                }
                else
                {
                  msgContext.addOutboundExecutedPhase(currentHandler);
                }
              }
              else
              {
                /* If we are resuming the flow, we don't want to add the phase 
                 * again, as it has already been added.
                 */
                resuming = false;
              }
              pi = currentHandler.invoke(msgContext);
            }
            catch (AxisFault e)
            {
              if (msgContext.getCurrentPhaseIndex() == 0)
              {
                /* If we got a fault, we still want to add the phase to the
                 list to be executed for flowComplete(...) unless this was
                 the first handler, as then the currentPhaseIndex will be
                 set to 0 and this will look like we've executed all of the
                 handlers.  If, at some point, a phase really needs to get
                 notification of flowComplete, then we'll need to introduce
                 some more complex logic to keep track of what has been
                 executed.*/ 
                if (inbound)
                {
                  msgContext.removeFirstInboundExecutedPhase();
                }
                else
                {
                  msgContext.removeFirstOutboundExecutedPhase();
                }
              }
              throw e;
            }

            if (pi.equals(InvocationResponse.SUSPEND) ||
                pi.equals(InvocationResponse.ABORT))
            {
              break;
            }

            msgContext.setCurrentHandlerIndex(msgContext.getCurrentHandlerIndex() + 1);
        }
        
        return pi;
    }

    private void flowComplete(MessageContext msgContext, boolean inbound) 
    {
      Iterator invokedPhaseIterator = inbound?msgContext.getInboundExecutedPhases():msgContext.getOutboundExecutedPhases(); 
      
      Handler currentHandler;
      while (invokedPhaseIterator.hasNext())
      {
        currentHandler = ((Handler)invokedPhaseIterator.next());
        currentHandler.flowComplete(msgContext);
      }
          
      /*This is needed because the OutInAxisOperation currently invokes
       * receive() even when a fault occurs, and we will have already executed
       * the flowComplete on those before receiveFault() is called.
       */
      if (inbound)
      {
        msgContext.resetInboundExecutedPhases();
      }
      else
      {
        msgContext.resetOutboundExecutedPhases();
      }
    }

    /**
     * If the msgConetext is puased and try to invoke then
     * first invoke the phase list and after the message receiver
     *
     * @param msgContext
     * @return An InvocationResponse allowing the invoker to perhaps determine
     *         whether or not the message processing will ever succeed.
     * @throws AxisFault
     */
    public InvocationResponse resumeReceive(MessageContext msgContext) throws AxisFault {
        if(log.isTraceEnabled()){
            log.trace("resumeReceive:"+msgContext.getMessageID());
        }
      //REVIEW: This name is a little misleading, as it seems to indicate that there should be a resumeReceiveFault as well, when, in fact, this does both 
      //REVIEW: Unlike with receive, there is no wrapping try/catch clause which would
      //fire off the flowComplete on an error, as we have to assume that the
      //message will be resumed again, but perhaps we need to unwind back to
      //the point at which the message was resumed and provide another API
      //to allow the full unwind if the message is going to be discarded.
        //invoke the phases
        InvocationResponse pi = invoke(msgContext, IS_INBOUND, RESUMING_EXECUTION);
        //invoking the MR
        
        if (pi.equals(InvocationResponse.CONTINUE))
        {
          if (msgContext.isServerSide())
          {
            // invoke the Message Receivers
            checkMustUnderstand(msgContext);
            MessageReceiver receiver = msgContext.getAxisOperation().getMessageReceiver();
            receiver.receive(msgContext);
          }
          flowComplete(msgContext, true);
        }
        
        return pi;
    }

    /**
     * To resume the invocation at the send path , this is neened since it is require to call
     * TransportSender at the end
     *
     * @param msgContext
     * @return An InvocationResponse allowing the invoker to perhaps determine
     *         whether or not the message processing will ever succeed.
     * @throws AxisFault
     */
    public InvocationResponse resumeSend(MessageContext msgContext) throws AxisFault {
        if(log.isTraceEnabled()){
            log.trace("resumeSend:"+msgContext.getMessageID());
        }
      //REVIEW: This name is a little misleading, as it seems to indicate that there should be a resumeSendFault as well, when, in fact, this does both 
      //REVIEW: Unlike with send, there is no wrapping try/catch clause which would
      //fire off the flowComplete on an error, as we have to assume that the
      //message will be resumed again, but perhaps we need to unwind back to
      //the point at which the message was resumed and provide another API
      //to allow the full unwind if the message is going to be discarded.
        //invoke the phases
        InvocationResponse pi = invoke(msgContext, IS_OUTBOUND, RESUMING_EXECUTION);
        //Invoking Transport Sender
        if (pi.equals(InvocationResponse.CONTINUE))
        {
            // write the Message to the Wire
            TransportOutDescription transportOut = msgContext.getTransportOut();
            TransportSender sender = transportOut.getSender();
            sender.invoke(msgContext);
            flowComplete(msgContext, false);
        }
        
        return pi;
    }

    /**
     * This is invoked when a SOAP Fault is received from a Other SOAP Node
     * Receives a SOAP fault from another SOAP node.
     *
     * @param msgContext
     * @throws AxisFault
     */
    public void receiveFault(MessageContext msgContext) throws AxisFault {
    	log.debug(Messages.getMessage("receivederrormessage",
                msgContext.getMessageID()));
        ConfigurationContext confContext = msgContext.getConfigurationContext();
        ArrayList preCalculatedPhases =
                confContext.getAxisConfiguration().getInFaultFlow();
        // Set the initial execution chain in the MessageContext to a *copy* of what
        // we got above.  This allows individual message processing to change the chain without
        // affecting later messages.
        msgContext.setExecutionChain((ArrayList) preCalculatedPhases.clone());
        msgContext.setFLOW(MessageContext.IN_FAULT_FLOW);
        
        try
        {
          InvocationResponse pi = invoke(msgContext, IS_INBOUND, NOT_RESUMING_EXECUTION);

          if (pi.equals(InvocationResponse.CONTINUE))
          {
            if (msgContext.isServerSide())
            {
              // invoke the Message Receivers
              checkMustUnderstand(msgContext);
              
              MessageReceiver receiver = msgContext.getAxisOperation().getMessageReceiver();

              receiver.receive(msgContext);
            }
            flowComplete(msgContext, true);
          }
          else if (pi.equals(InvocationResponse.SUSPEND))
          {
            return;
          }
          else if (pi.equals(InvocationResponse.ABORT))
          {
            flowComplete(msgContext, true);
            return;
          }
          else
          {
            String errorMsg = "Unrecognized InvocationResponse encountered in AxisEngine.receiveFault()";
            log.error(errorMsg);
            throw new AxisFault(errorMsg);
          }
        }
        catch (AxisFault e)
        {
          flowComplete(msgContext, true);
          throw e;
        }
    }

    /**
     * Resume processing of a message.
     * @param msgctx
     * @return An InvocationResponse allowing the invoker to perhaps determine
     *         whether or not the message processing will ever succeed.
     * @throws AxisFault
     */
    public InvocationResponse resume(MessageContext msgctx) throws AxisFault {
        if(log.isTraceEnabled()){
            log.trace("resume:"+msgctx.getMessageID());
        }
        msgctx.setPaused(false);
        if (msgctx.getFLOW() == MessageContext.IN_FLOW) {
            return resumeReceive(msgctx);
        } else {
            return resumeSend(msgctx);
        }
    }

    /**
     * This methods represents the outflow of the Axis, this could be either at the server side or the client side.
     * Here the <code>ExecutionChain</code> is created using the Phases. The Handlers at the each Phases is ordered in
     * deployment time by the deployment module
     *
     * @param msgContext
     * @throws AxisFault
     * @see MessageContext
     * @see Phase
     * @see Handler
     */
    public void send(MessageContext msgContext) throws AxisFault {
        if(log.isTraceEnabled()){
            log.trace("send:"+msgContext.getMessageID());
        }
        // find and invoke the Phases
        OperationContext operationContext = msgContext.getOperationContext();
        ArrayList executionChain = operationContext.getAxisOperation().getPhasesOutFlow();
        //rather than having two steps added both oparation and global chain together
        ArrayList outPhases = new ArrayList();
        outPhases.addAll((ArrayList) executionChain.clone());
        outPhases.addAll((ArrayList) msgContext.getConfigurationContext()
                .getAxisConfiguration().getGlobalOutPhases().clone());
        msgContext.setExecutionChain(outPhases);
        msgContext.setFLOW(MessageContext.OUT_FLOW);
        try
        {
          InvocationResponse pi = invoke(msgContext, IS_OUTBOUND, NOT_RESUMING_EXECUTION);

          if (pi.equals(InvocationResponse.CONTINUE))
          {
            // write the Message to the Wire
            TransportOutDescription transportOut = msgContext.getTransportOut();
            if(transportOut == null) {
                throw new AxisFault("Transport out has not been set");
            }
            TransportSender sender = transportOut.getSender();
            // This boolean property only used in client side fireAndForget invocation
            //It will set a property into message context and if some one has set the
            //property then transport sender will invoke in a diffrent thread
            Object isTransportNonBlocking = msgContext.getProperty(
                    MessageContext.TRANSPORT_NON_BLOCKING);
            if (isTransportNonBlocking != null && ((Boolean) isTransportNonBlocking).booleanValue()) {
                msgContext.getConfigurationContext().getThreadPool().execute(
                        new TransportNonBlockingInvocationWorker(msgContext, sender));
            } else {
                sender.invoke(msgContext);
            }
            //REVIEW: In the case of the TransportNonBlockingInvocationWorker, does this need to wait until that finishes?
            flowComplete(msgContext, false);
          }
          else if (pi.equals(InvocationResponse.SUSPEND))
          {
            return;
          }
          else if (pi.equals(InvocationResponse.ABORT))
          {
            flowComplete(msgContext, false);
            return;
          }
          else
          {
            String errorMsg = "Unrecognized InvocationResponse encountered in AxisEngine.send()";
            log.error(errorMsg);
            throw new AxisFault(errorMsg);
          }
        }
        catch (AxisFault e)
        {
          flowComplete(msgContext, false);          
          throw e;
        }
    }

    /**
     * Sends the SOAP Fault to another SOAP node.
     *
     * @param msgContext
     * @throws AxisFault
     */
    public void sendFault(MessageContext msgContext) throws AxisFault {
        if(log.isTraceEnabled()){
            log.trace("sendFault:"+msgContext.getMessageID());
        }
        OperationContext opContext = msgContext.getOperationContext();

        //FIXME: If this gets paused in the operation-specific phases, the resume is not going to function correctly as the phases will not have all been set 
        
        // find and execute the Fault Out Flow Handlers
        if (opContext != null) {
            AxisOperation axisOperation = opContext.getAxisOperation();
            ArrayList faultExecutionChain = axisOperation.getPhasesOutFaultFlow();

            //adding both operation specific and global out fault flows.
            
            ArrayList outFaultPhases = new ArrayList();
            outFaultPhases.addAll((ArrayList) faultExecutionChain.clone());
            msgContext.setExecutionChain((ArrayList) outFaultPhases.clone());
            msgContext.setFLOW(MessageContext.OUT_FAULT_FLOW);
            try
            {
              InvocationResponse pi = invoke(msgContext, IS_OUTBOUND, NOT_RESUMING_EXECUTION);
              
              if (pi.equals(InvocationResponse.SUSPEND))
              {
                log.warn("The resumption of this flow may function incorrectly, as the OutFaultFlow will not be used");
                return;
              }
              else if (pi.equals(InvocationResponse.ABORT))
              {
                flowComplete(msgContext, false);
                return;
              }
              else if (!pi.equals(InvocationResponse.CONTINUE))
              {
                String errorMsg = "Unrecognized InvocationResponse encountered in AxisEngine.sendFault()";
                log.error(errorMsg);
                throw new AxisFault(errorMsg);
              }
            }
            catch (AxisFault e)
            {
              flowComplete(msgContext, false);
              throw e;
            }
        }
        
        msgContext.setExecutionChain((ArrayList) msgContext.getConfigurationContext().getAxisConfiguration().getOutFaultFlow().clone());
        msgContext.setFLOW(MessageContext.OUT_FAULT_FLOW);
        InvocationResponse pi = invoke(msgContext, IS_OUTBOUND, NOT_RESUMING_EXECUTION);

        if (pi.equals(InvocationResponse.CONTINUE))
        {
          // Actually send the SOAP Fault
          TransportSender sender = msgContext.getTransportOut().getSender();

          sender.invoke(msgContext);
          flowComplete(msgContext, false);
        }
        else if (pi.equals(InvocationResponse.SUSPEND))
        {
          return;
        }
        else if (pi.equals(InvocationResponse.ABORT))
        {
          flowComplete(msgContext, false);
          return;
        }
        else
        {
          String errorMsg = "Unrecognized InvocationResponse encountered in AxisEngine.sendFault()";
          log.error(errorMsg);
          throw new AxisFault(errorMsg);
        }
        
    }

    /**
     * This class is used when someone invoke a service invocation with two transports
     * If we dont create a new thread then the main thread will block untill it gets the
     * response . In the case of HTTP transportsender will block untill it gets HTTP 200
     * So , main thread also block till transport sender rereases the tread. So there is no
     * actual non-blocking. That is why when sending we creat a new thead and send the
     * requset via that.
     * <p/>
     * So whole porpose of this class to send the requset via a new thread
     * <p/>
     * way transport.
     */
    private class TransportNonBlockingInvocationWorker implements Runnable {
        private MessageContext msgctx;
        private TransportSender sender;

        public TransportNonBlockingInvocationWorker(MessageContext msgctx,
                                                    TransportSender sender) {
            this.msgctx = msgctx;
            this.sender = sender;
        }

        public void run() {
            try {
                sender.invoke(msgctx);
            } catch (Exception e) {
              log.info(e.getMessage());
              if (msgctx.getProperty(MessageContext.DISABLE_ASYNC_CALLBACK_ON_TRANSPORT_ERROR) == null)
              {
                AxisOperation axisOperation = msgctx.getAxisOperation();
                if (axisOperation != null)
                {
                  MessageReceiver msgReceiver = axisOperation.getMessageReceiver();
                  if ((msgReceiver != null) && (msgReceiver instanceof CallbackReceiver))
                  {
                    Callback callback = ((CallbackReceiver)msgReceiver).lookupCallback(msgctx.getMessageID());
                    if (callback != null)
                    {
                      callback.onError(e);
                    }
                  }
                }
              }
            }
        }
    }
}