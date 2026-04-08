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

import org.apache.camel.CamelContext
import org.apache.camel.ConsumerTemplate
import org.apache.camel.ProducerTemplate
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.service.camel.CamelToolFactory
import spock.lang.Shared
import spock.lang.Specification

class CamelXmlRouteTests extends Specification {
    @Shared ExecutionContext ec
    @Shared CamelContext camelContext
    @Shared ConsumerTemplate consumerTemplate
    @Shared ProducerTemplate producerTemplate

    // IDs for recipe-export test data (seeded/cleaned in setupSpec/cleanupSpec)
    static final String RECIPE_CFG_ID = "CAMEL-RECIPE-CFG"
    static final String RECIPE_RSET_ID = "CAMEL-RECIPE-RSET"
    static final String RECIPE_RULE_ID = "CAMEL-RECIPE-RULE"
    static final String RECIPE_P1_ID = "CAMEL-RECIPE-P1"
    static final String RECIPE_P2_ID = "CAMEL-RECIPE-P2"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)

        // Seed recipe-export test data.
        // Depends on Plc4j seed: Device VIRTUAL_PLC and ParameterDef Reference/Feedback must exist.
        ec.entity.makeValue("mantle.device.DeviceConfig").setAll([
            deviceConfigId: RECIPE_CFG_ID, deviceTypeEnumId: "DtPLC",
            configName: "CamelRecipeTestConfig"
        ]).createOrUpdate()
        ec.entity.makeValue("mantle.device.DeviceRuleSet").setAll([
            deviceRuleSetId: RECIPE_RSET_ID, ruleSetName: "CamelRecipeTestRuleSet"
        ]).createOrUpdate()
        ec.entity.makeValue("mantle.device.DeviceRule").setAll([
            deviceRuleId: RECIPE_RULE_ID, deviceRuleSetId: RECIPE_RSET_ID,
            deviceConfigId: RECIPE_CFG_ID, deviceId: "VIRTUAL_PLC",
            ruleName: "CamelRecipeTestRule", priority: 1
        ]).createOrUpdate()
        // Parameters linked to the DeviceConfig (parameterAlias null → falls back to parameterName from ParameterDef)
        ec.entity.makeValue("mantle.math.Parameter").setAll([
            parameterId: RECIPE_P1_ID, parameterDefId: "Reference",
            numericValue: 300.0, deviceConfigId: RECIPE_CFG_ID
        ]).createOrUpdate()
        ec.entity.makeValue("mantle.math.Parameter").setAll([
            parameterId: RECIPE_P2_ID, parameterDefId: "Feedback",
            numericValue: 150.0, deviceConfigId: RECIPE_CFG_ID
        ]).createOrUpdate()

        ec.transaction.commit()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()

        camelContext = ec.ecfi.getToolFactory(CamelToolFactory.TOOL_NAME).getInstance() as CamelContext
        consumerTemplate = camelContext.createConsumerTemplate()
        producerTemplate = camelContext.createProducerTemplate()
        consumerTemplate.start()
        producerTemplate.start()
        drainAll()
    }

    def cleanupSpec() {
        producerTemplate?.stop()
        consumerTemplate?.stop()

        ec.user.loginUser("john.doe", "moqui")
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        ec.entity.find("mantle.math.Parameter").condition("parameterId", "in", [RECIPE_P1_ID, RECIPE_P2_ID] as List).deleteAll()
        ec.entity.find("mantle.device.DeviceRule").condition("deviceRuleId", RECIPE_RULE_ID).deleteAll()
        ec.entity.find("mantle.device.DeviceRuleSet").condition("deviceRuleSetId", RECIPE_RSET_ID).deleteAll()
        ec.entity.find("mantle.device.DeviceConfig").condition("deviceConfigId", RECIPE_CFG_ID).deleteAll()
        ec.transaction.commit()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()

        ec.destroy()
    }

    def setup() {
        ec.user.loginUser("john.doe", "moqui")
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        drainAll()
    }

    def cleanup() {
        ec.transaction.commit()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
    }

    // route loading

    def "xml routes are loaded into Camel context"() {
        expect:
        camelContext.routeIds.contains("moqui-outbound-data")
        camelContext.routeIds.contains("moqui-inbound-data")
        camelContext.routeIds.contains("moqui-recipe-export")
        camelContext.routeIds.contains("moqui-file-transfer")
    }

    // outbound: DeviceRequest read → JSON → broker (SEDA in tests)

    def "outbound route queries DeviceRequestItem+Parameter and publishes JSON"() {
        // Uses seed data from moqui-plc4j: SimulatedBackRead has items for parameterId 9000 and 9002.
        when:
        Map result = ec.service.sync()
            .name("moqui.camel.CamelConfigurationDrivenServices.triggerOutboundData")
            .parameters([requestName: "SimulatedBackRead"])
            .call()
        // SimulatedBackRead has 2 items; collect both messages (sorted by SEQUENCE_NUM)
        String json1 = consumerTemplate.receiveBody("seda:moquiOutbound", 3000, String.class)
        String json2 = consumerTemplate.receiveBody("seda:moquiOutbound", 1000, String.class)
        Map  audit1 = consumerTemplate.receiveBody("seda:moquiOutboundAudit", 1000, Map.class)

        then:
        result.status == "completed"
        result.routeId == "moqui-outbound-data"
        result.publishUri.startsWith("seda:moquiOutbound")
        // First row: parameterId 9000 (sequenceNum 1 in SimulatedBackRead)
        json1 != null && json1.contains('"parameterId":"9000"')
        json2 != null && json2.contains('"parameterId":"9002"')
        audit1?.parameterId == "9000"
    }

    // inbound: broker (SEDA) → JSON → INSERT ParameterLog

    def "inbound route inserts ParameterLog row from received JSON"() {
        when:
        producerTemplate.sendBody("seda:moquiInbound",
            """{"parameterId":"9000","numericValue":299.5}""")
        Map notified = consumerTemplate.receiveBody("seda:moquiInboundNotify", 3000, Map.class)
        String generatedLogId = notified?.parameterLogId as String
        def logEntry = generatedLogId ?
            ec.entity.find("mantle.math.ParameterLog").condition("parameterLogId", generatedLogId).one() : null

        then:
        notified != null
        notified.parameterId == "9000"
        generatedLogId                                                   // route generated a UUID
        logEntry != null
        (logEntry.numericValue as BigDecimal).compareTo(299.5G) == 0

        cleanup:
        if (generatedLogId)
            ec.entity.find("mantle.math.ParameterLog").condition("parameterLogId", generatedLogId).deleteAll()
    }

    // recipe export: DeviceRuleSet → Codesys TXT → file transfer (SEDA)

    def "recipe export route formats Codesys TXT lines and forwards to file transfer"() {
        // Uses test entities seeded in setupSpec: RECIPE_CFG_ID / RECIPE_RSET_ID / VIRTUAL_PLC
        // Parameters: RECIPE-P1 (Reference, 300.0) and RECIPE-P2 (Feedback, 150.0)
        // SQL orders by PARAMETER_CODE: "24.01" (Reference) < "24.02" (Feedback)
        when:
        Map result = ec.service.sync()
            .name("moqui.camel.CamelConfigurationDrivenServices.exportRecipe")
            .parameters([deviceRuleSetId: RECIPE_RSET_ID, deviceId: "VIRTUAL_PLC"])
            .call()
        // Default file.transfer.uri = seda:moquiRecipeOutput → recipe text lands here
        String recipe = consumerTemplate.receiveBody("seda:moquiRecipeOutput", 3000, String.class)

        then:
        result.status == "completed"
        result.routeId == "moqui-recipe-export"
        result.rowCount == 2
        recipe != null
        // Each line: parameterName:=parameterValue
        // parameterName = COALESCE(parameterAlias, parameterName from ParameterDef)
        // parameterAlias not set → falls back to pd.PARAMETER_NAME
        recipe.readLines().size() == 2
        recipe.contains("Reference:=")
        recipe.contains("Feedback:=")
        // VALUES in recipe order (24.01 Reference first, 24.02 Feedback second)
        recipe.indexOf("Reference") < recipe.indexOf("Feedback")
    }

    def "recipe export delivers to secondary PLC when file.transfer.uri.2.enabled=true"() {
        // Temporarily enable the secondary file transfer to verify redundant delivery.
        // We override the Camel property at runtime and restore it after the test.
        given:
        def pc = camelContext.propertiesComponent
        pc.overrideProperties["file.transfer.uri.2.enabled"] = "true"
        pc.overrideProperties["file.transfer.uri.2"] = "seda:moquiRecipeOutput2?waitForTaskToComplete=Never"

        when:
        Map result = ec.service.sync()
            .name("moqui.camel.CamelConfigurationDrivenServices.exportRecipe")
            .parameters([deviceRuleSetId: RECIPE_RSET_ID, deviceId: "VIRTUAL_PLC"])
            .call()
        String primary   = consumerTemplate.receiveBody("seda:moquiRecipeOutput",  3000, String.class)
        String secondary = consumerTemplate.receiveBody("seda:moquiRecipeOutput2", 3000, String.class)

        then:
        result.status == "completed"
        result.rowCount == 2
        primary   != null && primary.contains("Reference:=")
        secondary != null && secondary.contains("Reference:=")
        // Same content sent to both PLCs
        primary == secondary

        cleanup:
        pc.overrideProperties.remove("file.transfer.uri.2.enabled")
        pc.overrideProperties.remove("file.transfer.uri.2")
        drainQueue("seda:moquiRecipeOutput2")
    }

    // helpers

    protected void drainAll() {
        ["seda:moquiOutbound", "seda:moquiOutboundAudit", "seda:moquiInboundNotify",
         "seda:moquiRecipeOutput", "seda:moquiRecipeOutput2"].each { drainQueue(it) }
    }

    protected void drainQueue(String uri) {
        while (consumerTemplate.receiveBody(uri, 10) != null) {}
    }
}
