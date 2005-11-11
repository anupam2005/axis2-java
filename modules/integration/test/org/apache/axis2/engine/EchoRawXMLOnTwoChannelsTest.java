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

import junit.framework.TestCase;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.clientapi.AsyncResult;
import org.apache.axis2.clientapi.Call;
import org.apache.axis2.clientapi.Callback;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.engine.util.TestConstants;
import org.apache.axis2.integration.TestingUtils;
import org.apache.axis2.integration.UtilServer;
import org.apache.axis2.om.OMAbstractFactory;
import org.apache.axis2.om.OMElement;
import org.apache.axis2.om.OMFactory;
import org.apache.axis2.om.OMNamespace;
import org.apache.axis2.util.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.xml.namespace.QName;

public class EchoRawXMLOnTwoChannelsTest extends TestCase implements TestConstants {

    private Log log = LogFactory.getLog(getClass());


    private boolean finish = false;

    public EchoRawXMLOnTwoChannelsTest() {
        super(EchoRawXMLOnTwoChannelsTest.class.getName());
    }

    public EchoRawXMLOnTwoChannelsTest(String testName) {
        super(testName);
    }

    protected void setUp() throws Exception {
        UtilServer.start();
        UtilServer.getConfigurationContext().getAxisConfiguration()
                .engageModule(new QName("addressing"));

        AxisService service =
                Utils.createSimpleService(serviceName,
                        Echo.class.getName(),
                        operationName);
        UtilServer.deployService(service);

    }

    protected void tearDown() throws Exception {
        UtilServer.unDeployService(serviceName);
        UtilServer.stop();
    }


    public void testEchoXMLCompleteASync() throws Exception {
        AxisService service =
                Utils.createSimpleService(serviceName,
                        Echo.class.getName(),
                        operationName);

        ServiceContext serviceContext = UtilServer.createAdressedEnabledClientSide(
                service);

        OMFactory fac = OMAbstractFactory.getOMFactory();

        OMNamespace omNs = fac.createOMNamespace("http://localhost/my", "my");
        OMElement method = fac.createOMElement("echoOMElement", omNs);
        OMElement value = fac.createOMElement("myValue", omNs);
        value.setText("Isaac Assimov, the foundation Sega");
        method.addChild(value);

        Call call =
                new Call(
                serviceContext);
        call.engageModule(new QName(Constants.MODULE_ADDRESSING));

        try {
            call.setTo(targetEPR);
            call.setTransportInfo(Constants.TRANSPORT_HTTP,
                    Constants.TRANSPORT_HTTP,
                    true);
            call.setWsaAction(operationName.getLocalPart());
            Callback callback = new Callback() {
                public void onComplete(AsyncResult result) {
                    TestingUtils.campareWithCreatedOMElement(
                            result.getResponseEnvelope().getBody()
                            .getFirstElement());
                    finish = true;
                }

                public void reportError(Exception e) {
                    log.info(e.getMessage());
                    finish = true;
                }
            };

            call.invokeNonBlocking(operationName.getLocalPart(),
                    method,
                    callback);
            int index = 0;
            while (!finish) {
                Thread.sleep(1000);
                index++;
                if (index > 10) {
                    throw new AxisFault(
                            "Server was shutdown as the async response take too long to complete");
                }
            }
            log.info("send the reqest");
            call.close();
        } finally {
            call.close();
        }

    }
}
