package apoc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.InternalLog;
import org.neo4j.logging.internal.LogService;
import org.neo4j.service.Services;

/**
 * NOTE: this is a GLOBAL component, so only once per DBMS
 */
@ServiceProvider
public class RegisterComponentFactory extends ExtensionFactory<RegisterComponentFactory.Dependencies> {

    private InternalLog log;
    private GlobalProcedures globalProceduresRegistry;

    public RegisterComponentFactory() {
        super(ExtensionType.GLOBAL, "ApocRegisterComponent");
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, Dependencies dependencies) {
        globalProceduresRegistry = dependencies.globalProceduresRegistry();
        log = dependencies.log().getUserLog(RegisterComponentFactory.class);
        return new RegisterComponentLifecycle();
    }

    public interface Dependencies {
        LogService log();
        GlobalProcedures globalProceduresRegistry();
    }

    public class RegisterComponentLifecycle extends LifecycleAdapter {

        private final Map<Class, Map<String, Object>> resolvers = new ConcurrentHashMap<>();

        public void addResolver(String databaseNamme, Class clazz, Object instance) {
            Map<String, Object> classInstanceMap = resolvers.computeIfAbsent(clazz, s -> new ConcurrentHashMap<>());
            classInstanceMap.put(databaseNamme, instance);
        }

        public Map<Class, Map<String, Object>> getResolvers() {
            return resolvers;
        }

        @Override
        public void init() throws Exception {

            for (ApocGlobalComponents c: Services.loadAll(ApocGlobalComponents.class)) {
                for (Class clazz: c.getContextClasses()) {
                    resolvers.put(clazz, new ConcurrentHashMap<>());
                }
            }

            resolvers.forEach(
                    (clazz, dbFunctionMap) -> globalProceduresRegistry.registerComponent(clazz, context -> {
                        String databaseName = context.graphDatabaseAPI().databaseName();
                        Object instance = dbFunctionMap.get(databaseName);
                        if (instance == null) {
                            log.warn("couldn't find a instance for clazz %s and database %s", clazz.getName(), databaseName);
                        }
                        return instance;
                    }, true)
            );
        }

    }
}
