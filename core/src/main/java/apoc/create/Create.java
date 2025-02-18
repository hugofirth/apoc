package apoc.create;

import apoc.get.Get;
import apoc.result.*;
import apoc.util.Util;
import apoc.uuid.UuidUtil;
import org.neo4j.graphdb.*;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.procedure.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.neo4j.graphdb.RelationshipType.withName;

public class Create {

    public static final String[] EMPTY_ARRAY = new String[0];

    @Context
    public Transaction tx;

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.node(['Label'], {key:value,...}) - create node with dynamic labels")
    public Stream<NodeResult> node(@Name("label") List<String> labelNames, @Name("props") Map<String, Object> props) {
        return Stream.of(new NodeResult(setProperties(tx.createNode(Util.labels(labelNames)),props)));
    }


    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.addLabels( [node,id,ids,nodes], ['Label',...]) - adds the given labels to the node or nodes")
    public Stream<NodeResult> addLabels(@Name("nodes") Object nodes, @Name("label") List<String> labelNames) {
        Label[] labels = Util.labels(labelNames);
        return new Get(tx).nodes(nodes).map((r) -> {
            Node node = r.node;
            for (Label label : labels) {
                node.addLabel(label);
            }
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.setProperty( [node,id,ids,nodes], key, value) - sets the given property on the node(s)")
    public Stream<NodeResult> setProperty(@Name("nodes") Object nodes, @Name("key") String key, @Name("value") Object value) {
        return new Get(tx).nodes(nodes).map((r) -> {
            setProperty(r.node, key,toPropertyValue(value));
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.setRelProperty( [rel,id,ids,rels], key, value) - sets the given property on the relationship(s)")
    public Stream<RelationshipResult> setRelProperty(@Name("relationships") Object rels, @Name("key") String key, @Name("value") Object value) {
        return new Get(tx).rels(rels).map((r) -> {
            setProperty(r.rel,key,toPropertyValue(value));
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.setProperties( [node,id,ids,nodes], [keys], [values]) - sets the given properties on the nodes(s)")
    public Stream<NodeResult> setProperties(@Name("nodes") Object nodes, @Name("keys") List<String> keys, @Name("values") List<Object> values) {
        return new Get(tx).nodes(nodes).map((r) -> {
            setProperties(r.node, Util.mapFromLists(keys,values));
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.removeProperties( [node,id,ids,nodes], [keys]) - removes the given properties from the nodes(s)")
    public Stream<NodeResult> removeProperties(@Name("nodes") Object nodes, @Name("keys") List<String> keys) {
        return new Get(tx).nodes(nodes).map((r) -> {
            keys.forEach( r.node::removeProperty );
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.setRelProperties( [rel,id,ids,rels], [keys], [values]) - sets the given properties on the relationship(s)")
    public Stream<RelationshipResult> setRelProperties(@Name("rels") Object rels, @Name("keys") List<String> keys, @Name("values") List<Object> values) {
        return new Get(tx).rels(rels).map((r) -> {
            setProperties(r.rel, Util.mapFromLists(keys,values));
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.removeRelProperties( [rel,id,ids,rels], [keys]) - removes the given properties from the relationship(s)")
    public Stream<RelationshipResult> removeRelProperties(@Name("rels") Object rels, @Name("keys") List<String> keys) {
        return new Get(tx).rels(rels).map((r) -> {
            keys.forEach( r.rel::removeProperty);
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.setLabels( [node,id,ids,nodes], ['Label',...]) - sets the given labels, non matching labels are removed on the node or nodes")
    public Stream<NodeResult> setLabels(@Name("nodes") Object nodes, @Name("label") List<String> labelNames) {
        Label[] labels = Util.labels(labelNames);
        return new Get(tx).nodes(nodes).map((r) -> {
            Node node = r.node;
            for (Label label : node.getLabels()) {
                if (labelNames.contains(label.name())) continue;
                node.removeLabel(label);
            }
            for (Label label : labels) {
                if (node.hasLabel(label)) continue;
                node.addLabel(label);
            }
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.removeLabels( [node,id,ids,nodes], ['Label',...]) - removes the given labels from the node or nodes")
    public Stream<NodeResult> removeLabels(@Name("nodes") Object nodes, @Name("label") List<String> labelNames) {
        Label[] labels = Util.labels(labelNames);
        return new Get(tx).nodes(nodes).map((r) -> {
            Node node = r.node;
            for (Label label : labels) {
                node.removeLabel(label);
            }
            return r;
        });
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.nodes(['Label'], [{key:value,...}]) create multiple nodes with dynamic labels")
    public Stream<NodeResult> nodes(@Name("label") List<String> labelNames, @Name("props") List<Map<String, Object>> props) {
        Label[] labels = Util.labels(labelNames);
        return props.stream().map(p -> new NodeResult(setProperties(tx.createNode(labels), p)));
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.create.relationship(person1,'KNOWS',{key:value,...}, person2) create relationship with dynamic rel-type")
    public Stream<RelationshipResult> relationship(@Name("from") Node from,
                                                   @Name("relType") String relType, @Name("props") Map<String, Object> props,
                                                   @Name("to") Node to) {
        VirtualRelationship.validateNodes(from, to);
        return Stream.of(new RelationshipResult(setProperties(from.createRelationshipTo(to, withName(relType)), props)));
    }

    @Procedure
    @Description("apoc.create.vNode(['Label'], {key:value,...}) returns a virtual node")
    public Stream<NodeResult> vNode(@Name("label") List<String> labelNames, @Name("props") Map<String, Object> props) {
        return Stream.of(new NodeResult(vNodeFunction(labelNames, props)));
    }

    @UserFunction("apoc.create.vNode")
    @Description("apoc.create.vNode(['Label'], {key:value,...}) returns a virtual node")
    public Node vNodeFunction(@Name("label") List<String> labelNames, @Name(value = "props",defaultValue = "{}") Map<String, Object> props) {
        return new VirtualNode(Util.labels(labelNames), props);
    }

    @UserFunction("apoc.create.virtual.fromNode")
    @Description("apoc.create.virtual.fromNode(node, [propertyNames]) returns a virtual node built from an existing node with only the requested properties")
    public Node virtualFromNodeFunction(@Name("node") Node node, @Name("propertyNames") List<String> propertyNames) {
        return new VirtualNode(node, propertyNames);
    }

    @Procedure
    @Description("apoc.create.vNodes(['Label'], [{key:value,...}]) returns virtual nodes")
    public Stream<NodeResult> vNodes(@Name("label") List<String> labelNames, @Name("props") List<Map<String, Object>> props) {
        Label[] labels = Util.labels(labelNames);
        return props.stream().map(p -> new NodeResult(new VirtualNode(labels, p)));
    }

    @Procedure
    @Description("apoc.create.vRelationship(nodeFrom,'KNOWS',{key:value,...}, nodeTo) returns a virtual relationship")
    public Stream<RelationshipResult> vRelationship(@Name("from") Node from, @Name("relType") String relType, @Name("props") Map<String, Object> props, @Name("to") Node to) {
        return Stream.of(new RelationshipResult(vRelationshipFunction(from, relType, props, to)));
    }

    @UserFunction("apoc.create.vRelationship")
    @Description("apoc.create.vRelationship(nodeFrom,'KNOWS',{key:value,...}, nodeTo) returns a virtual relationship")
    public Relationship vRelationshipFunction(@Name("from") Node from, @Name("relType") String relType, @Name("props") Map<String, Object> props, @Name("to") Node to) {
        return new VirtualRelationship(from, to, withName(relType)).withProperties(props);
    }

    @Procedure
    @Description("apoc.create.virtualPath(['LabelA'],{key:value},'KNOWS',{key:value,...},['LabelB'],{key:value}) returns a virtual path of nodes joined by a relationship and the associated properties")
    public Stream<VirtualPathResult> virtualPath(@Name("labelsN") List<String> labelsN, @Name("n") Map<String, Object> n,
                                                  @Name("relType") String relType, @Name("props") Map<String, Object> props,
                                                  @Name("labelsM") List<String> labelsM, @Name("m") Map<String, Object> m) {
        RelationshipType type = withName(relType);
        VirtualNode from = new VirtualNode(Util.labels(labelsN), n);
        VirtualNode to = new VirtualNode(Util.labels(labelsM), m);
        Relationship rel = new VirtualRelationship(from, to, type).withProperties(props);
        return Stream.of(new VirtualPathResult(from, rel, to));
    }

    @Procedure
    @Description("apoc.create.clonePathToVirtual")
    public Stream<PathResult> clonePathToVirtual(@Name("path") Path path) {
        return Stream.of(createVirtualPath(path));
    }

    @Procedure
    @Description("apoc.create.clonePathsToVirtual")
    public Stream<PathResult> clonePathsToVirtual(@Name("paths") List<Path> paths) {
        return paths.stream().map(this::createVirtualPath);
    }

    private PathResult createVirtualPath(Path path) {
        final Iterable<Relationship> relationships = path.relationships();
        final Node first = path.startNode();
        VirtualPath virtualPath = new VirtualPath(new VirtualNode(first, Iterables.asList(first.getPropertyKeys())));
        for (Relationship rel : relationships) {
            VirtualNode start = VirtualNode.from(rel.getStartNode());
            VirtualNode end = VirtualNode.from(rel.getEndNode());
            virtualPath.addRel(VirtualRelationship.from(start, end, rel));
        }
        return new PathResult(virtualPath);
    }

    private <T extends Entity> T setProperties(T pc, Map<String, Object> p) {
        if (p == null) return pc;
        for (Map.Entry<String, Object> entry : p.entrySet()) {
            setProperty(pc, entry.getKey(), entry.getValue());
        }
        return pc;
    }

    private <T extends Entity> void setProperty(T pc, String key, Object value) {
        if (value == null) pc.removeProperty(key);
        else pc.setProperty(key, toPropertyValue(value));
    }

    @UserFunction(deprecatedBy = "Neo4j randomUUID() function")
    @Deprecated
    @Description("apoc.create.uuid() - creates a UUID")
    public String uuid() {
        return UUID.randomUUID().toString();
    }

    @UserFunction
    @Description("apoc.create.uuidBase64() - create a UUID encoded with Base64")
    public String uuidBase64() {
        return UuidUtil.generateBase64Uuid(UUID.randomUUID());
    }

    @UserFunction
    @Description("apoc.create.uuidBase64ToHex() - convert between a UUID encoded with Base64 to HEX format")
    public String uuidBase64ToHex(@Name("base64Uuid") String base64Uuid) {
        return UuidUtil.fromBase64ToHex(base64Uuid);
    }

    @UserFunction
    @Description("apoc.create.uuidHexToBase64() - convert a UUID in HEX format to encoded with Base64")
    public String uuidHexToBase64(@Name("uuidHex") String uuidHex) {
        return UuidUtil.fromHexToBase64(uuidHex);
    }

    private Object toPropertyValue(Object value) {
        if (value instanceof Iterable) {
            Iterable it = (Iterable) value;
            Object first = Iterables.firstOrNull(it);
            if (first == null) return EMPTY_ARRAY;
            return Iterables.asArray(first.getClass(), it);
        }
        return value;
    }

    @Procedure(deprecatedBy = "Neo4j's randomUUID() function can be used as a replacement, for example: `UNWIND range(0,$count) AS row RETURN row, randomUUID() AS uuid`")
    @Deprecated
    @Description("apoc.create.uuids(count) yield uuid - creates 'count' UUIDs ")
    public Stream<UUIDResult> uuids(@Name("count") long count) {
        return LongStream.range(0, count).mapToObj(UUIDResult::new);
    }

    public static class UUIDResult {
        public final long row;
        public final String uuid;

        public UUIDResult(long row) {
            this.row = row;
            this.uuid = UUID.randomUUID().toString();
            // TODO Long.toHexString(uuid.getMostSignificantBits())+Long.toHexString(uuid.getLeastSignificantBits());
        }
    }

}
