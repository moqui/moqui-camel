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
package org.moqui.camel.routes;

import java.util.HashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MoquiDemoRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        from("file://runtime/component/moqui-camel/data?fileName=demoData.json&noop=true")
                .convertBodyTo(String.class)
                .process(new Processor() {
                    @Override @SuppressWarnings("unchecked")
                    public void process(Exchange exchange) throws Exception {
                        String body = exchange.getIn().getBody(String.class);
                        Map<String, Object> bodyMap = new ObjectMapper().readValue(body, HashMap.class);
                        exchange.getOut().setBody(bodyMap, Map.class);
                    }
                })
                .to("moquiservice://moqui.example.ExampleServices.targetCamelExample");
    }
}