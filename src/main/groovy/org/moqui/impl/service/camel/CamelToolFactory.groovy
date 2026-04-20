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

import java.net.URI
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import groovy.transform.CompileStatic
import org.apache.camel.CamelContext
import org.apache.camel.component.properties.PropertiesComponent
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.spi.Resource
import org.apache.camel.spi.RoutesLoader
import org.apache.camel.support.ResourceHelper
import org.apache.camel.support.SimpleRegistry
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.impl.entity.EntityFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** A ToolFactory for Apache Camel, an Enterprise Integration Patterns toolkit used for message processing and
 * integrated with the ServiceFacade with an end point to use services to produce and consume Camel messages. */
@CompileStatic
class CamelToolFactory implements ToolFactory<CamelContext> {
    protected final static Logger logger = LoggerFactory.getLogger(CamelToolFactory.class)
    final static String TOOL_NAME = "Camel"
    final static String COMPONENT_NAME = "moqui-camel"
    final static String DEFAULT_DATASOURCE_NAME = "moquiDataSource"
    final static String SYS_PROP_PREFIX = "moqui.camel."

    protected ExecutionContextFactory ecf = null
    /** The central object of the Camel API: CamelContext */
    protected CamelContext camelContext
    protected SimpleRegistry camelRegistry
    protected MoquiServiceComponent moquiServiceComponent
    protected Map<String, MoquiServiceConsumer> camelConsumerByUriMap = new ConcurrentHashMap<>()
    protected final Properties loadedProperties = new Properties()
    protected final List<String> loadedRouteLocations = new CopyOnWriteArrayList<>()

    /** Default empty constructor */
    CamelToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }

    @Override
    void init(ExecutionContextFactory ecf) {
        logger.info("Starting Camel")
        configureCamelRegistry()
        configureCamelProperties()
        moquiServiceComponent = new MoquiServiceComponent(this)
        camelContext.addComponent("moquiservice", moquiServiceComponent)
        loadXmlRoutes()
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
        try {
            camelContext?.stop()
            logger.info("Camel stopped")
        } catch (Throwable t) { 
            logger.error("Error in Camel stop", t) 
        }
    }

    ExecutionContextFactory getEcf() { return ecf }
    MoquiServiceComponent getMoquiServiceComponent() { return moquiServiceComponent }
    void registerCamelConsumer(String uri, MoquiServiceConsumer consumer) { camelConsumerByUriMap.put(uri, consumer) }
    MoquiServiceConsumer getCamelConsumer(String uri) { return camelConsumerByUriMap.get(uri) }
    Properties getLoadedProperties() { return loadedProperties }
    List<String> getLoadedRouteLocations() { return loadedRouteLocations }

    protected void configureCamelRegistry() {
        camelRegistry.bind("moquiExecutionContextFactory", ExecutionContextFactory.class, ecf)
        camelRegistry.bind("camelToolFactory", CamelToolFactory.class, this)

        EntityFacadeImpl efi = ecf.entity as EntityFacadeImpl
        DataSource defaultDataSource = null
        for (Map<String, Object> dsInfo in efi.dataSourcesInfo) {
            String groupName = dsInfo["group"] as String
            DataSource ds = efi.getDatasourceFactory(groupName)?.getDataSource()
            if (ds == null) continue

            camelRegistry.bind(groupName, DataSource.class, ds)
            defaultDataSource = defaultDataSource ?: ds
            logger.info("Registered Camel DataSource binding [{}] for entity group [{}]", groupName, groupName)
        }

        if (defaultDataSource != null) {
            camelRegistry.bind(DEFAULT_DATASOURCE_NAME, DataSource.class, defaultDataSource)
            logger.info("Registered Camel default DataSource binding [{}]", DEFAULT_DATASOURCE_NAME)
        } else {
            logger.warn("No JDBC DataSource found for Camel SQL routes; sql endpoints that use registry bindings will fail")
        }
    }

    protected void configureCamelProperties() {
        Properties componentProperties = new Properties()
        loadPropertiesFromDir(componentProperties, camelConfigDir, false)
        loadPropertiesFromDir(componentProperties, Path.of(ecf.runtimePath, "conf"), true)

        Properties overrideProperties = new Properties()
        System.properties.each { Object k, Object v ->
            String key = k as String
            if (key.startsWith(SYS_PROP_PREFIX))
                overrideProperties.setProperty(key.substring(SYS_PROP_PREFIX.length()), v as String)
        }

        org.apache.camel.spi.PropertiesComponent pc = camelContext.propertiesComponent ?: new PropertiesComponent()
        pc.setNestedPlaceholder(true)
        pc.setInitialProperties(componentProperties)
        pc.setLocalProperties(componentProperties)
        pc.setOverrideProperties(overrideProperties)
        camelContext.setPropertiesComponent(pc)

        loadedProperties.clear()
        loadedProperties.putAll(componentProperties)
        loadedProperties.putAll(overrideProperties)
        logger.info("Configured Camel properties with {} defaults and {} overrides", componentProperties.size(), overrideProperties.size())
    }

    protected void loadXmlRoutes() {
        Path routeDir = camelRoutesDir
        if (!Files.isDirectory(routeDir)) {
            logger.info("No Camel XML route directory found at {}", routeDir)
            return
        }

        RoutesLoader routesLoader = camelContext.camelContextExtension.getContextPlugin(RoutesLoader.class)
        if (routesLoader == null)
            throw new IllegalStateException("Camel RoutesLoader not available. Add camel-xml-io-dsl to the moqui-camel component dependencies.")

        List<Path> routeFiles = []
        collectRouteFiles(routeDir, routeFiles)
        routeFiles.sort()

        for (Path routeFile in routeFiles) {
            Resource resource = ResourceHelper.resolveMandatoryResource(camelContext, routeFile.toUri().toString())
            routesLoader.loadRoutes(resource)
            loadedRouteLocations << routeFile.toAbsolutePath().toString()
            logger.info("Loaded Camel XML routes from {}", routeFile)
        }
    }

    protected void loadPropertiesFromDir(Properties target, Path dir, boolean confOnly) {
        if (!Files.isDirectory(dir)) return

        List<Path> propertyFiles = []
        (Files.newDirectoryStream(dir) as DirectoryStream<Path>).withCloseable { ds ->
            for (Path path in ds) {
                String name = path.fileName.toString()
                if (Files.isRegularFile(path) && name.endsWith(".properties") && (!confOnly || name.startsWith("camel")))
                    propertyFiles << path
            }
        }
        propertyFiles.sort()

        for (Path f in propertyFiles) {
            Files.newInputStream(f).withCloseable { target.load(it) }
            logger.info("Loaded Camel properties from {}", f)
        }
    }

    protected void collectRouteFiles(Path dir, List<Path> routeFiles) {
        (Files.newDirectoryStream(dir) as DirectoryStream<Path>).withCloseable { ds ->
            for (Path path in ds) {
                if (Files.isDirectory(path)) collectRouteFiles(path, routeFiles)
                else if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".xml")) routeFiles << path
            }
        }
    }

    protected Path getCamelConfigDir() { componentBaseDir.resolve("camel") }
    protected Path getCamelRoutesDir() { camelConfigDir.resolve("routes") }

    protected Path getComponentBaseDir() {
        String loc = ecf.componentBaseLocations.get(COMPONENT_NAME)
        if (!loc) throw new IllegalStateException("Could not find component base location for ${COMPONENT_NAME}")
        return loc.startsWith("file:") ? Path.of(new URI(loc)) : Path.of(loc)
    }
}
