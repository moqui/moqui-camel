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

import org.apache.camel.Endpoint
import org.apache.camel.spi.annotations.Component
import org.apache.camel.support.DefaultComponent

@Component("moquiservice")
class MoquiServiceComponent extends DefaultComponent {

    private final CamelToolFactory camelToolFactory

    MoquiServiceComponent(CamelToolFactory ctf) {
        this.camelToolFactory = ctf
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        MoquiServiceEndpoint endpoint = new MoquiServiceEndpoint(uri, this, remaining)
        setProperties(endpoint, parameters)
        return endpoint
    }

    CamelToolFactory getCamelToolFactory() { return camelToolFactory }
}
