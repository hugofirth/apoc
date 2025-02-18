package org.neo4j.test.rule;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.neo4j.common.DependencyResolver;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.Neo4jDatabaseManagementServiceBuilder;
import org.neo4j.dbms.database.TopologyGraphDbmsModel.HostedOnMode;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.ResultTransformer;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.impl.factory.DbmsInfo;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

@Deprecated
public abstract class DbmsRule extends ExternalResource implements GraphDatabaseAPI
{
    private Neo4jDatabaseManagementServiceBuilder databaseBuilder;
    private GraphDatabaseAPI database;
    private boolean startEagerly = true;
    private final Map<Setting<?>, Object> globalConfig = new HashMap<>();
    private DatabaseManagementService managementService;

    /**
     * Means the database will be started on first {@link #getGraphDatabaseAPI()}}
     * or {@link #ensureStarted()} call.
     */
    public DbmsRule startLazily()
    {
        startEagerly = false;
        return this;
    }

    public <T> T executeAndCommit( Function<Transaction, T> function )
    {
        return transaction( function, true );
    }

    public <T> T executeAndRollback( Function<Transaction, T> function )
    {
        return transaction( function, false );
    }

    public <FROM, TO> Function<FROM,TO> tx( Function<FROM,TO> function )
    {
        return from ->
        {
            Function<Transaction,TO> inner = graphDb -> function.apply( from );
            return executeAndCommit( inner );
        };
    }

    private <T> T transaction( Function<Transaction, T> function, boolean commit )
    {
        return tx( getGraphDatabaseAPI(), commit, function );
    }

    /**
     * Perform a transaction, with the option to automatically retry on failure.
     *
     * @param db {@link GraphDatabaseService} to apply the transaction on.
     * @param transaction {@link Consumer} containing the transaction logic.
     */
    public static void tx( GraphDatabaseService db, Consumer<Transaction> transaction )
    {
        Function<Transaction,Void> voidFunction = tx ->
        {
            transaction.accept( tx );
            return null;
        };
        tx( db, true, voidFunction );
    }

    /**
     * Perform a transaction, with the option to automatically retry on failure.
     * Also returning a result from the supplied transaction function.
     *
     * @param db {@link GraphDatabaseService} to apply the transaction on.
     * @param commit whether or not to call {@link Transaction#commit()} in the end.
     * @param transaction {@link Function} containing the transaction logic and returning a result.
     * @return result from transaction {@link Function}.
     */
    public static <T> T tx( GraphDatabaseService db, boolean commit, Function<Transaction,T> transaction )
    {
        try ( Transaction tx = db.beginTx() )
        {
            T result = transaction.apply( tx );
            if ( commit )
            {
                tx.commit();
            }
            return result;
        }
    }

    @Override
    public void executeTransactionally( String query ) throws QueryExecutionException
    {
        getGraphDatabaseAPI().executeTransactionally( query );
    }

    @Override
    public void executeTransactionally( String query, Map<String,Object> parameters ) throws QueryExecutionException
    {
        getGraphDatabaseAPI().executeTransactionally( query, parameters );
    }

    @Override
    public <T> T executeTransactionally( String query, Map<String,Object> parameters, ResultTransformer<T> resultTransformer ) throws QueryExecutionException
    {
        return getGraphDatabaseAPI().executeTransactionally( query, parameters, resultTransformer );
    }

    @Override
    public <T> T executeTransactionally( String query, Map<String,Object> parameters, ResultTransformer<T> resultTransformer, Duration timeout )
            throws QueryExecutionException
    {
        return getGraphDatabaseAPI().executeTransactionally( query, parameters, resultTransformer, timeout );
    }

    @Override
    public InternalTransaction beginTransaction( KernelTransaction.Type type, LoginContext loginContext )
    {
        return getGraphDatabaseAPI().beginTransaction( type, loginContext );
    }

    @Override
    public InternalTransaction beginTransaction( KernelTransaction.Type type, LoginContext loginContext, ClientConnectionInfo connectionInfo )
    {
        return getGraphDatabaseAPI().beginTransaction( type, loginContext, connectionInfo );
    }

    @Override
    public InternalTransaction beginTransaction( KernelTransaction.Type type, LoginContext loginContext, ClientConnectionInfo connectionInfo, long timeout,
            TimeUnit unit )
    {
        return getGraphDatabaseAPI().beginTransaction( type, loginContext, connectionInfo, timeout, unit );
    }

    @Override
    public Transaction beginTx()
    {
        return getGraphDatabaseAPI().beginTx();
    }

    @Override
    public Transaction beginTx( long timeout, TimeUnit timeUnit )
    {
        return getGraphDatabaseAPI().beginTx( timeout, timeUnit );
    }

    @Override
    protected void before()
    {
        create();
        if ( startEagerly )
        {
            ensureStarted();
        }
    }

    @Override
    protected void after( boolean success )
    {
        shutdown( success );
    }

    private void create()
    {
        createResources();
        try
        {
            databaseBuilder = newFactory();
            configure( databaseBuilder );
            databaseBuilder.setConfig( globalConfig );
        }
        catch ( RuntimeException e )
        {
            deleteResources();
            throw e;
        }
    }

    protected void deleteResources()
    {
    }

    protected void createResources()
    {
    }

    protected abstract Neo4jDatabaseManagementServiceBuilder newFactory();

    protected void configure( Neo4jDatabaseManagementServiceBuilder databaseFactory )
    {
        // Override to configure the database factory
    }

    /**
     * {@link DbmsRule} now implements {@link GraphDatabaseAPI} directly, so no need. Also for ensuring
     * a lazily started database is created, use {@link #ensureStarted()} instead.
     */
    public GraphDatabaseAPI getGraphDatabaseAPI()
    {
        ensureStarted();
        return database;
    }

    public DatabaseManagementService getManagementService()
    {
        return managementService;
    }

    public synchronized void ensureStarted()
    {
        if ( database == null )
        {
            managementService = databaseBuilder.build();
            database = (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );
        }
    }

    /**
     * Adds or replaces a setting for the database managed by this database rule.
     * <p>
     * If this method is called when constructing the rule, the setting is considered a global setting applied to all tests.
     * <p>
     * If this method is called inside a specific test, i.e. after {@link #before()}, but before started (a call to {@link #startLazily()} have been made),
     * then this setting will be considered a test-specific setting, adding to or overriding the global settings for this test only.
     * Test-specific settings will be remembered throughout a test, even between restarts.
     * <p>
     * If this method is called when a database is already started an {@link IllegalStateException} will be thrown since the setting
     * will have no effect, instead letting the developer notice that and change the test code.
     */
    public <T> DbmsRule withSetting( Setting<T> key, T value )
    {
        if ( database != null )
        {
            // Database already started
            throw new IllegalStateException( "Wanted to set " + key + "=" + value + ", but database has already been started" );
        }
        if ( databaseBuilder != null )
        {
            // Test already started, but db not yet started
            databaseBuilder.setConfig( key, value );
        }
        else
        {
            // Test haven't started, we're still in phase of constructing this rule
            globalConfig.put( key, value );
        }
        return this;
    }

    /**
     * Applies all settings in the settings map.
     *
     * @see #withSetting(Setting, Object)
     */
    public DbmsRule withSettings( Map<Setting<?>,Object> configuration )
    {
        if ( database != null )
        {
            // Database already started
            throw new IllegalStateException( "Wanted to set " + configuration + ", but database has already been started" );
        }
        if ( databaseBuilder != null )
        {
            // Test already started, but db not yet started
            databaseBuilder.setConfig( configuration );
        }
        else
        {
            // Test haven't started, we're still in phase of constructing this rule
            globalConfig.putAll( configuration );
        }
        return this;
    }

    public GraphDatabaseAPI restartDatabase() throws IOException
    {
        return restartDatabase( Map.of() );
    }

    public GraphDatabaseAPI restartDatabase( Map<Setting<?>,Object> configChanges ) throws IOException
    {
        managementService.shutdown();
        database = null;
        // This DatabaseBuilder has already been configured with the global settings as well as any test-specific settings,
        // so just apply these additional settings.
        databaseBuilder.setConfig( configChanges );
        return getGraphDatabaseAPI();
    }

    public void shutdown()
    {
        shutdown( true );
    }

    private void shutdown( boolean deleteResources )
    {
        try
        {
            if ( managementService != null )
            {
                managementService.shutdown();
            }
        }
        finally
        {
            if ( deleteResources )
            {
                deleteResources();
            }
            managementService = null;
            database = null;
        }
    }

    public void shutdownAndKeepStore()
    {
        shutdown( false );
    }

    public <T> T resolveDependency( Class<T> type )
    {
        return getGraphDatabaseAPI().getDependencyResolver().resolveDependency( type );
    }

    @Override
    public NamedDatabaseId databaseId()
    {
        return database.databaseId();
    }

    @Override
    public DbmsInfo dbmsInfo()
    {
        return database.dbmsInfo();
    }

    @Override
    public HostedOnMode mode()
    {
        return database.mode();
    }

    @Override
    public DependencyResolver getDependencyResolver()
    {
        return database.getDependencyResolver();
    }

    @Override
    public DatabaseLayout databaseLayout()
    {
        return database.databaseLayout();
    }

    @Override
    public boolean isAvailable( long timeout )
    {
        return database.isAvailable( timeout );
    }

    @Override
    public boolean isAvailable()
    {
        return database.isAvailable();
    }

    @Override
    public String databaseName()
    {
        return database.databaseName();
    }
}
