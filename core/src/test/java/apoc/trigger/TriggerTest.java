package apoc.trigger;

import apoc.nodes.Nodes;
import apoc.util.TestUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.coreapi.TransactionImpl;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static apoc.ApocSettings.apoc_trigger_enabled;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.neo4j.configuration.GraphDatabaseSettings.procedure_unrestricted;
import static org.neo4j.internal.helpers.collection.MapUtil.map;

/**
 * @author mh
 * @since 20.09.16
 */
public class TriggerTest {

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule()
            .withSetting(procedure_unrestricted, List.of("apoc*"))
            .withSetting(apoc_trigger_enabled, true);  // need to use settings here, apocConfig().setProperty in `setUp` is too late

    private long start;

    @Before
    public void setUp() throws Exception {
        start = System.currentTimeMillis();
        TestUtil.registerProcedure(db, Trigger.class, Nodes.class);
    }

    @Test
    public void testListTriggers() throws Exception {
        String query = "MATCH (c:Counter) SET c.count = c.count + size([f IN $deletedNodes WHERE id(f) > 0])";

        TestUtil.testCallCount(db, "CALL apoc.trigger.add('count-removals',$query,{}) YIELD name RETURN name",
                map("query", query),
                1);
        TestUtil.testCall(db, "CALL apoc.trigger.list()", (row) -> {
            assertEquals("count-removals", row.get("name"));
            assertEquals(query, row.get("query"));
            assertEquals(true, row.get("installed"));
        });
    }

    @Test
    public void testRemoveNode() throws Exception {
        db.executeTransactionally("CREATE (:Counter {count:0})");
        db.executeTransactionally("CREATE (f:Foo)");
        db.executeTransactionally("CALL apoc.trigger.add('count-removals','MATCH (c:Counter) SET c.count = c.count + size([f IN $deletedNodes WHERE id(f) > 0])',{})");
        db.executeTransactionally("MATCH (f:Foo) DELETE f");
        TestUtil.testCall(db, "MATCH (c:Counter) RETURN c.count as count", (row) -> {
            assertEquals(1L, row.get("count"));
        });
    }

    @Test
    public void testIssue2247() {
        db.executeTransactionally("CREATE (n:ToBeDeleted)");
        db.executeTransactionally("CALL apoc.trigger.add('myTrig', 'RETURN 1', {phase: 'afterAsync'})");

        db.executeTransactionally("MATCH (n:ToBeDeleted) DELETE n");

        db.executeTransactionally("CALL apoc.trigger.remove('myTrig')");
    }

    @Test
    public void testRemoveRelationship() throws Exception {
        db.executeTransactionally("CREATE (:Counter {count:0})");
        db.executeTransactionally("CREATE (f:Foo)-[:X]->(f)");
        db.executeTransactionally("CALL apoc.trigger.add('count-removed-rels','MATCH (c:Counter) SET c.count = c.count + size($deletedRelationships)',{})");
        db.executeTransactionally("MATCH (f:Foo) DETACH DELETE f");
        TestUtil.testCall(db, "MATCH (c:Counter) RETURN c.count as count", (row) -> {
            assertEquals(1L, row.get("count"));
        });
    }

    @Test
    public void testRemoveTrigger() throws Exception {
        TestUtil.testCallCount(db, "CALL apoc.trigger.add('to-be-removed','RETURN 1',{}) YIELD name RETURN name", 1);
        TestUtil.testCall(db, "CALL apoc.trigger.list()", (row) -> {
            assertEquals("to-be-removed", row.get("name"));
            assertEquals("RETURN 1", row.get("query"));
            assertEquals(true, row.get("installed"));
        });
        TestUtil.testCall(db, "CALL apoc.trigger.remove('to-be-removed')", (row) -> {
            assertEquals("to-be-removed", row.get("name"));
            assertEquals("RETURN 1", row.get("query"));
            assertEquals(false, row.get("installed"));
        });

        TestUtil.testCallCount(db, "CALL apoc.trigger.list()", 0);
        TestUtil.testCall(db, "CALL apoc.trigger.remove('to-be-removed')", (row) -> {
            assertEquals("to-be-removed", row.get("name"));
            assertEquals(null, row.get("query"));
            assertEquals(false, row.get("installed"));
        });
    }

    @Test
    public void testRemoveAllTrigger() throws Exception {
        TestUtil.testCallCount(db, "CALL apoc.trigger.removeAll()", 0);
        TestUtil.testCallCount(db, "CALL apoc.trigger.add('to-be-removed-1','RETURN 1',{}) YIELD name RETURN name", 1);
        TestUtil.testCallCount(db, "CALL apoc.trigger.add('to-be-removed-2','RETURN 2',{}) YIELD name RETURN name", 1);
        TestUtil.testCallCount(db, "CALL apoc.trigger.list()", 2);
        TestUtil.testResult(db, "CALL apoc.trigger.removeAll()", (res) -> {
            Map<String, Object> row = res.next();
            assertEquals("to-be-removed-1", row.get("name"));
            assertEquals("RETURN 1", row.get("query"));
            assertEquals(false, row.get("installed"));
            row = res.next();
            assertEquals("to-be-removed-2", row.get("name"));
            assertEquals("RETURN 2", row.get("query"));
            assertEquals(false, row.get("installed"));
            assertFalse(res.hasNext());
        });
        TestUtil.testCallCount(db, "CALL apoc.trigger.list()", 0);
        TestUtil.testCallCount(db, "CALL apoc.trigger.removeAll()", 0);
    }

    @Test
    public void testTimeStampTrigger() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('timestamp','UNWIND $createdNodes AS n SET n.ts = timestamp()',{})");
        db.executeTransactionally("CREATE (f:Foo)");
        TestUtil.testCall(db, "MATCH (f:Foo) RETURN f", (row) -> {
            assertEquals(true, ((Node) row.get("f")).hasProperty("ts"));
        });
    }

    @Test
    public void testTxIdAfterAsync() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('txinfo','UNWIND $createdNodes AS n SET n.txId = $transactionId, n.txTime = $commitTime',{phase:'afterAsync'})");
        db.executeTransactionally("CREATE (f:Bar)");
        org.neo4j.test.assertion.Assert.assertEventually(() ->
                        db.executeTransactionally("MATCH (n:Bar) RETURN n", Map.of(),
                                result -> {
                                    final Node node = result.<Node>columnAs("n").next();
                                    return (long) node.getProperty("txId", -1L) > -1L
                                            && (long) node.getProperty("txTime") > start;
                                })
                , (value) -> value, 30L, TimeUnit.SECONDS);
    }

    @Test
    public void testTxId() {
        db.executeTransactionally("CREATE (f:Another)");
        db.executeTransactionally("CALL apoc.trigger.add('txinfo','UNWIND $createdNodes AS n \n" +
                "MATCH (a:Another) WITH a, n\n" +
                "SET a.txId = $transactionId, a.txTime = $commitTime',{phase:'after'})");
        db.executeTransactionally("CREATE (f:Bar)");
        TestUtil.testCall(db, "MATCH (f:Another) RETURN f", (row) -> {
            assertEquals(true, (Long) ((Node) row.get("f")).getProperty("txId") > -1L);
            assertEquals(true, (Long) ((Node) row.get("f")).getProperty("txTime") > start);
        });
        db.executeTransactionally("MATCH (n:Another) DELETE n");
    }

    @Test
    public void testMetaDataBefore() {
        db.executeTransactionally("CALL apoc.trigger.add('txinfo','UNWIND $createdNodes AS n SET n.label = labels(n)[0], n += $metaData', {phase: 'before'})");
        testMetaData("MATCH (n:Bar) RETURN n");
    }

    @Test
    public void testMetaDataAfter() {
        db.executeTransactionally("CREATE (n:Another)");
        db.executeTransactionally("CALL apoc.trigger.add('txinfo', 'UNWIND $createdNodes AS n MATCH (a:Another) SET a.label = labels(n)[0], a += $metaData', {phase: 'after'})");
        testMetaData("MATCH (n:Another) RETURN n");
        db.executeTransactionally("MATCH (n:Another) DELETE n");
    }

    private void testMetaData(String matchQuery) {
        try (Transaction tx = db.beginTx()) {
            KernelTransaction ktx = ((TransactionImpl)tx).kernelTransaction();
            ktx.setMetaData(Collections.singletonMap("txMeta", "hello"));
            tx.execute("CREATE (f:Bar)");
            tx.commit();
        }
        TestUtil.testCall(db, matchQuery, (row) -> {
            final Node node = (Node) row.get("n");
            assertEquals("Bar", node.getProperty("label"));
            assertEquals("hello",  node.getProperty("txMeta"));
        });
    }

    @Test
    public void testPauseResult() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('pausedTest', 'UNWIND $createdNodes AS n SET n.txId = $transactionId, n.txTime = $commitTime', {phase: 'after'})");
        TestUtil.testCall(db, "CALL apoc.trigger.pause('pausedTest')", (row) -> {
            assertEquals("pausedTest", row.get("name"));
            assertEquals(true, row.get("installed"));
            assertEquals(true, row.get("paused"));
        });
    }

    @Test
    public void testPauseOnCallList() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('test', 'UNWIND $createdNodes AS n SET n.txId = $transactionId, n.txTime = $commitTime', {phase: 'after'})");
        db.executeTransactionally("CALL apoc.trigger.pause('test')");
        TestUtil.testCall(db, "CALL apoc.trigger.list()", (row) -> {
            assertEquals("test", row.get("name"));
            assertEquals(true, row.get("installed"));
            assertEquals(true, row.get("paused"));
        });
    }

    @Test
    public void testResumeResult() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('test', 'UNWIND $createdNodes AS n SET n.txId = $transactionId, n.txTime = $commitTime', {phase: 'after'})");
        db.executeTransactionally("CALL apoc.trigger.pause('test')");
        TestUtil.testCall(db, "CALL apoc.trigger.resume('test')", (row) -> {
            assertEquals("test", row.get("name"));
            assertEquals(true, row.get("installed"));
            assertEquals(false, row.get("paused"));
        });
    }

    @Test
    public void testTriggerPause() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('test','UNWIND $createdNodes AS n SET n.txId = $transactionId, n.txTime = $commitTime',{})");
        db.executeTransactionally("CALL apoc.trigger.pause('test')");
        db.executeTransactionally("CREATE (f:Foo {name:'Michael'})");
        TestUtil.testCall(db, "MATCH (f:Foo) RETURN f", (row) -> {
            assertEquals(false, ((Node) row.get("f")).hasProperty("txId"));
            assertEquals(false, ((Node) row.get("f")).hasProperty("txTime"));
            assertEquals(true, ((Node) row.get("f")).hasProperty("name"));
        });
    }

    @Test
    public void testTriggerResume() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('test','UNWIND $createdNodes AS n SET n.txId = $transactionId, n.txTime = $commitTime',{})");
        db.executeTransactionally("CALL apoc.trigger.pause('test')");
        db.executeTransactionally("CALL apoc.trigger.resume('test')");
        db.executeTransactionally("CREATE (f:Foo {name:'Michael'})");
        TestUtil.testCall(db, "MATCH (f:Foo) RETURN f", (row) -> {
            assertEquals(true, ((Node) row.get("f")).hasProperty("txId"));
            assertEquals(true, ((Node) row.get("f")).hasProperty("txTime"));
            assertEquals(true, ((Node) row.get("f")).hasProperty("name"));
        });
    }

    @Test(expected = QueryExecutionException.class)
    public void showThrowAnException() throws Exception {
        db.executeTransactionally("CALL apoc.trigger.add('test','UNWIND $createdNodes AS n SET n.txId = , n.txTime = $commitTime',{})");
    }

    @Test
    public void testCreatedRelationshipsAsync() throws Exception {
        db.executeTransactionally("CREATE (:A {name: \"A\"})-[:R1]->(:Z {name: \"Z\"})");
        db.executeTransactionally("CALL apoc.trigger.add('trigger-after-async', 'UNWIND $createdRelationships AS r\n" +
                "MATCH (a:A)-[r]->(z:Z)\n" +
                "WHERE type(r) IN [\"R2\", \"R3\"]\n" +
                "MATCH (a)-[r1:R1]->(z)\n" +
                "SET r1.triggerAfterAsync = true', {phase: 'afterAsync'})");
        db.executeTransactionally("MATCH (a:A {name: \"A\"})-[:R1]->(z:Z {name: \"Z\"})\n" +
                "MERGE (a)-[:R2]->(z)");

        org.neo4j.test.assertion.Assert.assertEventually(() ->
            db.executeTransactionally("MATCH ()-[r:R1]->() RETURN r", Map.of(),
                    result -> (boolean) result.<Relationship>columnAs("r").next()
                            .getProperty("triggerAfterAsync", false))
            , (value) -> value, 30L, TimeUnit.SECONDS);
    }

    @Test
    public void testDeleteRelationshipsAsync() {
        db.executeTransactionally("CREATE (a:A {name: \"A\"})-[:R1 {omega: 3}]->(z:Z {name: \"Z\"}), (a)-[:R2 {alpha: 1}]->(z)");
        final String query = "UNWIND $deletedRelationships AS r\n" +
                "MATCH (a)-[r1:R1]->(z)\n" +
                "SET a.alpha = apoc.any.property(r, \"alpha\"), r1.triggerAfterAsync = size($deletedRelationships) > 0, r1.size = size($deletedRelationships), r1.deleted = type(r) RETURN *";
        db.executeTransactionally("CALL apoc.trigger.add('trigger-after-async-1', $query, {phase: 'afterAsync'})",
                map("query", query));

        // delete rel
        commonDeleteAfterAsync("MATCH (a:A {name: 'A'})-[r:R2]->(z:Z {name: 'Z'}) DELETE r");
    }

    @Test
    public void testDeleteRelationshipsAsyncWithCreationInQuery() {
        db.executeTransactionally("CREATE (a:A {name: \"A\"})-[:R1 {omega: 3}]->(z:Z {name: \"Z\"}), (a)-[:R2 {alpha: 1}]->(z)");
        final String query = "UNWIND $deletedRelationships AS r\n" +
                "CREATE (a:A)-[r1:R1 {omega: 3}]->(z)\n" +
                "SET a.alpha = apoc.any.property(r, \"alpha\"), r1.triggerAfterAsync = size($deletedRelationships) > 0, r1.size = size($deletedRelationships), r1.deleted = type(r) RETURN *";
        db.executeTransactionally("CALL apoc.trigger.add('trigger-after-async-2', $query, {phase: 'afterAsync'})",
                map("query", query));

        // delete rel
        commonDeleteAfterAsync("MATCH (a:A {name: 'A'})-[r:R2]->(z:Z {name: 'Z'}) DELETE r");
    }

    @Test
    public void testDeleteNodesAsync() {
        db.executeTransactionally("CREATE (a:A {name: 'A'})-[:R1 {omega: 3}]->(z:Z {name: 'Z'}), (:R2:Other {alpha: 1})");
        final String query = "UNWIND $deletedNodes AS n\n" +
                "MATCH (a)-[r1:R1]->(z)\n" +
                "SET a.alpha = apoc.any.property(n, \"alpha\"), r1.triggerAfterAsync = size($deletedNodes) > 0, r1.size = size($deletedNodes), r1.deleted = apoc.node.labels(n)[0] RETURN *";
        
        db.executeTransactionally("CALL apoc.trigger.add('trigger-after-async-3', $query, {phase: 'afterAsync'})", 
                map("query", query));

        // delete node
        commonDeleteAfterAsync("MATCH (n:R2) DELETE n");
    }

    @Test
    public void testDeleteNodesAsyncWithCreationQuery() {
        db.executeTransactionally("CREATE (:R2:Other {alpha: 1})");
        final String query = "UNWIND $deletedNodes AS n\n" +
                "CREATE (a:A)-[r1:R1 {omega: 3}]->(z:Z)\n" +
                "SET a.alpha = apoc.any.property(n, \"alpha\"), r1.triggerAfterAsync = size($deletedNodes) > 0, r1.size = size($deletedNodes), r1.deleted = apoc.node.labels(n)[0] RETURN *";
        
        db.executeTransactionally("CALL apoc.trigger.add('trigger-after-async-4', $query, {phase: 'afterAsync'})", 
                map("query", query));

        // delete node
        commonDeleteAfterAsync("MATCH (n:R2) DELETE n");
    }
    
    private void commonDeleteAfterAsync(String deleteQuery) {
        db.executeTransactionally(deleteQuery);
        
        final Map<String, Object> expectedProps = Map.of("deleted", "R2",
                "triggerAfterAsync", true,
                "size", 1L,
                "omega", 3L);

        org.neo4j.test.assertion.Assert.assertEventually(() ->
                        db.executeTransactionally("MATCH (a:A {alpha: 1})-[r:R1]->() RETURN r", Map.of(),
                                result -> {
                                    final ResourceIterator<Relationship> relIterator = result.columnAs("r");
                                    return relIterator.hasNext()
                                            && relIterator.next().getAllProperties().equals(expectedProps);
                                })
                , (value) -> value, 30L, TimeUnit.SECONDS);
    }

    @Test
    public void testDeleteRelationships() throws Exception {
        db.executeTransactionally("CREATE (a:A {name: \"A\"})-[:R1]->(z:Z {name: \"Z\"}), (a)-[:R2]->(z)");
        db.executeTransactionally("CALL apoc.trigger.add('trigger-after', 'UNWIND $deletedRelationships AS r\n" +
                "MERGE (a:AA{name: \"AA\"})\n" +
                "SET a.triggerAfter = size($deletedRelationships) = 1, a.deleted = type(r)', {phase: 'after'})");
        db.executeTransactionally("MATCH (a:A {name: \"A\"})-[r:R2]->(z:Z {name: \"Z\"})\n" +
                "DELETE r");

        org.neo4j.test.assertion.Assert.assertEventually(() ->
                        db.executeTransactionally("MATCH (a:AA) RETURN a", Map.of(),
                                result -> {
                                    final Node r = result.<Node>columnAs("a").next();
                                    return (boolean) r.getProperty("triggerAfter", false)
                                            && r.getProperty("deleted", "").equals("R2");
                                })
                , (value) -> value, 30L, TimeUnit.SECONDS);
    }


}
