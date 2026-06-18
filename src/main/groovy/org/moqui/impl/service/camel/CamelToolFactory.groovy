/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.service.camel

import java.util.concurrent.ConcurrentHashMap
import groovy.transform.CompileStatic
import org.apache.camel.CamelContext
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.SimpleRegistry
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** A ToolFactory for Apache Camel, an Enterprise Integration Patterns toolkit used for message processing and
 * integrated with the ServiceFacade with an end point to use services to produce and consume Camel messages. */
@CompileStatic
class CamelToolFactory implements ToolFactory<CamelContext> {
    protected final static Logger logger = LoggerFactory.getLogger(CamelToolFactory.class)
    final static String TOOL_NAME = "Camel"

    protected ExecutionContextFactory ecf = null
    /** The central object of the Camel API: CamelContext */
    protected CamelContext camelContext
    protected SimpleRegistry camelRegistry
    protected MoquiServiceComponent moquiServiceComponent
    protected Map<String, MoquiServiceConsumer> camelConsumerByUriMap = new ConcurrentHashMap<>()

    /** Default empty constructor */
    CamelToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }

    @Override
    void init(ExecutionContextFactory ecf) {
        logger.info("Starting Camel")
        if (this.ecf == null) this.ecf = ecf
        if (camelRegistry == null) camelRegistry = new SimpleRegistry()
        if (camelContext == null) camelContext = new DefaultCamelContext(camelRegistry)
        configureCamelRegistry()
        moquiServiceComponent = new MoquiServiceComponent(this)
        camelContext.addComponent("moquiservice", moquiServiceComponent)
        camelContext.start()
    }
    
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf
        // setup the CamelContext, but don't init moquiservice Camel Component yet
        camelRegistry = new SimpleRegistry()
        camelContext = new DefaultCamelContext(camelRegistry)
    }

    @Override
    CamelContext getInstance(Object... parameters) { return camelContext }

    @Override
    void destroy() {
        // stop Camel to prevent more calls coming in
        if (camelContext != null) try {
            camelContext.stop()
            logger.info("Camel stopped")
        } catch (Throwable t) { logger.error("Error in Camel stop", t) }
    }

    ExecutionContextFactory getEcf() { return ecf }
    MoquiServiceComponent getMoquiServiceComponent() { return moquiServiceComponent }
    void registerCamelConsumer(String uri, MoquiServiceConsumer consumer) { camelConsumerByUriMap.put(uri, consumer) }
    MoquiServiceConsumer getCamelConsumer(String uri) { return camelConsumerByUriMap.get(uri) }

    protected void configureCamelRegistry() {
        camelRegistry.bind("moquiExecutionContextFactory", ExecutionContextFactory.class, ecf)
        camelRegistry.bind("camelToolFactory", CamelToolFactory.class, this)
    }
}
