# Moqui Apache Camel Tool Component

[![license](http://img.shields.io/badge/license-CC0%201.0%20Universal-blue.svg)](https://github.com/moqui/moqui-camel/blob/master/LICENSE.md)
[![release](http://img.shields.io/github/release/moqui/moqui-camel.svg)](https://github.com/moqui/moqui-camel/releases)

Moqui Framework tool component for Apache Camel, an enterprise integration pattern (EIP) suite, along with an endpoint for the Service Facade.

To install run (with moqui-framework):

    $ ./gradlew getComponent -Pcomponent=moqui-camel

This will add the component to the Moqui runtime/component directory. 

The Apache Camel and dependent JAR files are added to the lib directory when the build is run for this component, which is
designed to be done from the Moqui build (ie from the moqui root directory) along with all other component builds. 

To use just install this component. The configuration for the ToolFactory and ServiceRunner is already in place in the 
MoquiConf.xml included in this component and will be merged with the main configuration at runtime.

## XML Routes and Configuration-Driven Routing

The component now auto-loads Camel XML routes from:

    runtime/component/moqui-camel/camel/routes

And it merges route properties from:

    runtime/component/moqui-camel/camel/*.properties
    runtime/conf/camel*.properties

Properties in `runtime/conf` override the component defaults. You can also override any property from the command line
with JVM system properties prefixed with `moqui.camel.`. For example:

    ./gradlew run -Dmoqui.camel.outbound.publish.uri=paho-mqtt5:iot/sensors/out?brokerUrl=tcp://localhost:1883

The component registers Moqui JDBC DataSources in Camel under:

    moquiDataSource
    <entity-group-name>

The two built-in routes are:

    moqui-outbound-data  Moqui service / REST trigger → SQL (DeviceRequestItem JOIN Parameter) → JSON rows → broker
    moqui-inbound-data   broker → JSON → SQL INSERT (ParameterLog) → optional Moqui service callback

Both routes are fully configurable: SQL, broker endpoint URI and optional post-step URI are all properties.
The primary broker target is MQTT v5 (paho-mqtt5), but any Camel endpoint (JMS, AMQP, SEDA, …) works.
For the inbound route, `parameterLogId` is generated automatically inside Camel if the incoming JSON does not provide it.

The default `camel-routes.properties` uses internal `seda:` endpoints so the component remains safe to start without an
MQTT broker. For a broker-backed setup copy settings from:

    runtime/component/moqui-camel/camel/camel-routes-mqtt.properties.example

and place the desired overrides in:

    runtime/conf/camel-routes.properties
