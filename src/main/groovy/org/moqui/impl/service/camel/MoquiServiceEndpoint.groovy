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

import org.apache.camel.Category
import org.apache.camel.Consumer
import org.apache.camel.Processor
import org.apache.camel.Producer
import org.apache.camel.spi.Metadata
import org.apache.camel.spi.UriEndpoint
import org.apache.camel.spi.UriPath
import org.apache.camel.support.DefaultEndpoint

@UriEndpoint(
    firstVersion = "1.0.0", 
    scheme = "moquiservice", 
    title = "Moqui Service", 
    syntax = "moquiservice:serviceName", 
    category = [ Category.CORE ]
)
class MoquiServiceEndpoint extends DefaultEndpoint {

    @UriPath(description = "The full name of the Moqui service to call")
    @Metadata(required = true)
    private String remaining
    private final CamelToolFactory camelToolFactory

    MoquiServiceEndpoint(String uri, MoquiServiceComponent component, String remaining) {
        super(uri, component)
        this.remaining = remaining
        this.camelToolFactory = component.getCamelToolFactory()
    }

    @Override
    Producer createProducer() throws Exception {
        return new MoquiServiceProducer(this, remaining)
    }

    @Override
    Consumer createConsumer(Processor processor) throws Exception {
        return new MoquiServiceConsumer(this, processor, remaining)
    }

    @Override boolean isSingleton() { return true }
    CamelToolFactory getCamelToolFactory() { return camelToolFactory }
}
