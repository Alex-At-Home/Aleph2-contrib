/*******************************************************************************
 * Copyright 2016, The IKANOW Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package com.ikanow.aleph2.graph.titan.utils;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.IoCore;
import org.junit.Test;
import org.mockito.Mockito;

import scala.Tuple2;
import scala.Tuple4;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ikanow.aleph2.core.shared.utils.BatchRecordUtils;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IBatchRecord;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.MockSecurityService;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.GraphSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.EnrichmentControlMetadataBean;
import com.ikanow.aleph2.data_model.objects.data_import.GraphAnnotationBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.BucketUtils;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.graph.titan.data_model.SimpleDecompConfigBean;
import com.ikanow.aleph2.graph.titan.data_model.SimpleDecompConfigBean.SimpleDecompElementBean;
import com.ikanow.aleph2.graph.titan.services.GraphDecompEnrichmentContext;
import com.ikanow.aleph2.graph.titan.services.GraphMergeEnrichmentContext;
import com.ikanow.aleph2.graph.titan.services.SimpleGraphDecompService;
import com.ikanow.aleph2.graph.titan.services.SimpleGraphMergeService;
import com.ikanow.aleph2.graph.titan.utils.TitanGraphBuildingUtils.MutableStatsBean;
import com.thinkaurelius.titan.core.Cardinality;
import com.thinkaurelius.titan.core.TitanFactory;
import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.core.TitanTransaction;
import com.thinkaurelius.titan.core.schema.TitanManagement;

import fj.data.Validation;

/**
 * @author Alex
 *
 */
public class TestTitanGraphBuildingUtils {
	final protected static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());

	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////
	////////////////////////////////
	
	// UTILS - PUBLIC UTILS
	
	@Test
	public void test_validateMergedElement() {
		
		// Graph schema (currently ignored)
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class).done().get();
		
		// Valid object:
		final ObjectNode base_test = _mapper.createObjectNode();
		base_test.put(GraphAnnotationBean.label, "test_label");
		base_test.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.vertex.toString());
		
		// 1) Check it validates:
		{
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(base_test, graph_schema);
			assertTrue("Should be valid: " + ret_val.validation(fail -> fail.message(), success -> ""), ret_val.isSuccess());
			assertEquals("Should return the inserted object: " + ret_val.success() + " vs " + base_test, base_test.toString(), ret_val.success().toString());
		}
		// 2) Couple of other valid incarnations:
		{
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.label, "test_label");
			test.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.edge.toString());
			test.put(GraphAnnotationBean.properties, _mapper.createObjectNode());
			
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(test, graph_schema);
			assertTrue("Should be valid: " + ret_val.validation(fail -> fail.message(), success -> ""), ret_val.isSuccess());
			assertEquals("Should return the inserted object: " + ret_val.success() + " vs " + test, test.toString(), ret_val.success().toString());
		}
		
		// 3.1) No type:
		{			
			final ObjectNode test = base_test.deepCopy();
			test.remove(GraphAnnotationBean.type);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());
		}
		// 3.2) Type not a string
		{			
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.type, 4L);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());
		}
		// 3.3) Type the wrong string
		{
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.type, "banana");
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());			
		}
		// 4.1) No label:
		{			
			final ObjectNode test = base_test.deepCopy();
			test.remove(GraphAnnotationBean.label);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());
		}
		// 4.2) label not a string
		{			
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.label, 4L);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());
		}
		// 5) Properties not an object
		{			
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.properties, 4L);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateMergedElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());
		}
	}

	@Test
	public void test_validateUserElement() {
				
		// Graph schema
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
					.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
				.done().get();
		
		// Valid object:
		final ObjectNode base_test = _mapper.createObjectNode();
		base_test.put(GraphAnnotationBean.label, "test_label");
		base_test.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.vertex.toString());
		base_test.put(GraphAnnotationBean.id, 0L);
		
		// 1.1) Check it validates:
		{
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateUserElement(base_test, graph_schema);
			assertTrue("Should be valid: " + ret_val.validation(fail -> fail.message(), success -> ""), ret_val.isSuccess());
			assertEquals("Should return the inserted object: " + ret_val.success() + " vs " + base_test, base_test.toString(), ret_val.success().toString());
		}
		// 1.2) Other valid incarnations
		{
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.id, _mapper.createObjectNode().put("name", "a").put("type", "b"));
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateUserElement(test, graph_schema);
			assertEquals("Should return the inserted object: " + ret_val.success() + " vs " + test, test.toString(), ret_val.success().toString());			
		}
		//(edge)
		{
			final ObjectNode test = base_test.deepCopy();
			test.remove(GraphAnnotationBean.id);
			test.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.edge.toString());
			test.put(GraphAnnotationBean.inV, _mapper.createObjectNode().put("name", "a").put("type", "b"));
			test.put(GraphAnnotationBean.outV, 0L);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateUserElement(test, graph_schema);
			assertEquals("Should return the inserted object: " + ret_val.success() + " vs " + test, test.toString(), ret_val.success().toString());			
		}
		// 2) Check fails out if the "validateMergedElement" bit fails
		{
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.type, "rabbit");
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateUserElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());			
		}
		// 3.1) id not present
		{
			final ObjectNode test = base_test.deepCopy();
			test.remove(GraphAnnotationBean.id);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateUserElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());			
		}
		// 3.2) id not a long/object
		{
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.id, 1.5);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateUserElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());			
		}
		// 3.3) id is an object but doesn't match the dedup fields
		{
			final ObjectNode test = base_test.deepCopy();
			test.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.edge.toString());
			test.put(GraphAnnotationBean.inV, _mapper.createObjectNode().put("name", "a"));
			test.put(GraphAnnotationBean.outV, 0L);
			final Validation<BasicMessageBean, ObjectNode> ret_val = TitanGraphBuildingUtils.validateUserElement(test, graph_schema);
			assertFalse("Should be invalid", ret_val.isSuccess());			
		}
		
	}
	
	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////
	////////////////////////////////
	
	// UTILS - LOW LEVEL 
	
	@Test
	public void test_getElementProperties() {
		// Graph schema
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
					.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
				.done().get();
		
		final TitanGraph titan = getSimpleTitanGraph();
		final TitanTransaction tx = titan.buildTransaction().start();

		Vertex v = tx.addVertex("test");
		v.property("name", "name_val");
		v.property("type", "type_val");
		v.property("misc", "misc_val");
		final JsonNode result = TitanGraphBuildingUtils.getElementProperties(v, graph_schema.deduplication_fields());
		
		final ObjectNode key = _mapper.createObjectNode();
		key.put("name", "name_val");
		key.put("type", "type_val");
		
		assertEquals(key.toString(), result.toString());
		
		tx.commit();
	}
	
	@Test
	public void test_insertIntoObjectNode() {
		final TitanGraph titan = getSimpleTitanGraph();
		final TitanTransaction tx = titan.buildTransaction().start();

		Vertex v = tx.addVertex("test");
		v.property("long", 3L);
		v.property("integer", 3);
		v.property("boolean", true);
		v.property("double", 3.1);
		v.property("str", "string");
		
		final ObjectNode graphson = _mapper.createObjectNode();
		
		TitanGraphBuildingUtils.insertIntoObjectNode("long", v.property("long"), graphson);
		TitanGraphBuildingUtils.insertIntoObjectNode("integer", v.property("integer"), graphson);
		TitanGraphBuildingUtils.insertIntoObjectNode("boolean", v.property("boolean"), graphson);
		TitanGraphBuildingUtils.insertIntoObjectNode("double", v.property("double"), graphson);
		TitanGraphBuildingUtils.insertIntoObjectNode("str", v.property("str"), graphson);

		final ObjectNode user_graphson = _mapper.createObjectNode();
		user_graphson.put("long", 3L);
		user_graphson.put("integer", 3);
		user_graphson.put("boolean", true);
		user_graphson.put("double", 3.1);
		user_graphson.put("str", "string");

		assertEquals(graphson.toString(), user_graphson.toString());
		
		tx.commit();
	}
	
	@Test
	public void test_jsonNodeToObject() throws JsonProcessingException, IOException {
		assertEquals(3L, ((Number)TitanGraphBuildingUtils.jsonNodeToObject(_mapper.readTree("3"))).longValue());
		assertEquals(3.1, ((Double)TitanGraphBuildingUtils.jsonNodeToObject(_mapper.readTree("3.1"))).doubleValue(), 0.00000001);
		assertEquals(true, ((Boolean)TitanGraphBuildingUtils.jsonNodeToObject(_mapper.readTree("true"))).booleanValue());
		assertEquals("alex", TitanGraphBuildingUtils.jsonNodeToObject(new TextNode("alex")));
	}
	
	@Test
	public void test_convertToObject() {
	
		// Graph schema
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
					.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
				.done().get();
		
		final ObjectNode graphson = _mapper.createObjectNode().put("name", "alex");
		
		assertEquals(graphson, TitanGraphBuildingUtils.convertToObject(graphson, graph_schema));
		assertEquals(graphson.toString(), TitanGraphBuildingUtils.convertToObject(new TextNode("alex"), graph_schema).toString());
	}
	
	@Test
	public void test_labelMatches() {
		final TitanGraph titan = getSimpleTitanGraph();
		final TitanTransaction tx = titan.buildTransaction().start();

		final Vertex test_vertex = tx.addVertex("test_vertex");				
		{
			final ObjectNode graphson = _mapper.createObjectNode().put("label", "test_vertex");
			assertTrue(TitanGraphBuildingUtils.labelMatches(graphson, test_vertex));
		}
		{
			final ObjectNode graphson = _mapper.createObjectNode().put("label", "test_vertex2");
			assertFalse(TitanGraphBuildingUtils.labelMatches(graphson, test_vertex));
		}		
		tx.commit();		
	}
	
	@Test
	public void test_isAllowed() {
		final MockSecurityService mock_security_service = new MockSecurityService();
		final TitanGraph titan = getSimpleTitanGraph();
		final TitanTransaction tx = titan.buildTransaction().start();
		// No permissions
		{
			final Vertex test_vertex = tx.addVertex("test_vertex");		
			assertTrue(TitanGraphBuildingUtils.isAllowed(Tuples._2T("nobody", mock_security_service), test_vertex));
		}
		// Fails/succeeds based on permission
		{
			final Vertex test_vertex = tx.addVertex("test_vertex");		
			test_vertex.property(GraphAnnotationBean.a2_p, "/test");
			assertFalse(TitanGraphBuildingUtils.isAllowed(Tuples._2T("nobody", mock_security_service), test_vertex));			

			mock_security_service.setGlobalMockRole("nobody:DataBucketBean:read,write:test:*", true);
			assertTrue(TitanGraphBuildingUtils.isAllowed(Tuples._2T("nobody", mock_security_service), test_vertex));			
		}
		// Just double check with edge...
		{
			final Vertex test_edge = tx.addVertex("test_vertex");		
			test_edge.property(GraphAnnotationBean.a2_p, "/test");
			assertTrue(TitanGraphBuildingUtils.isAllowed(Tuples._2T("nobody", mock_security_service), test_edge));			
			
		}
		tx.commit();		
	}
	
	@Test
	public void test_buildPermission() {
		assertEquals("DataBucketBean:read,write:alex:test", TitanGraphBuildingUtils.buildPermission("/alex/test"));
	}
	
	@Test
	public void test_insertProperties() {
		//(tested by test_invokeUserMergeCode, see below)
		//TODO (ALEPH-15): test the Long[], Double[], Boolean[] properties also
	}
	@Test
	public void test_denestProperties() {
		//(tested by test_invokeUserMergeCode, see below)		
	}
	
	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////
	////////////////////////////////
	
	// UTILS - HIGH LEVEL

	@Test
	public void test_groupNewEdgesAndVertices() {
		
		// Graph schema
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
					.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
				.done().get();

		final ObjectNode key1 = _mapper.createObjectNode()
									.put(GraphAnnotationBean.name, "1.1.1.1")
									.put(GraphAnnotationBean.type, "ip-addr");

		final ObjectNode key2 = _mapper.createObjectNode()
									.put(GraphAnnotationBean.name, "host.com")
									.put(GraphAnnotationBean.type, "domain-name");
		
		
		final List<ObjectNode> test_vertices_and_edges = Arrays.asList(
				(ObjectNode) _mapper.createObjectNode()
					.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.vertex.toString())
					.put(GraphAnnotationBean.label, "1.1.1.1")
					.set(GraphAnnotationBean.id, key1
							)
				,
				(ObjectNode) _mapper.createObjectNode() //(invalid node, no type)
					.put(GraphAnnotationBean.label, "1.1.1.2")
					.set(GraphAnnotationBean.id, _mapper.createObjectNode()
													.put(GraphAnnotationBean.name, "1.1.1.2")
													.put(GraphAnnotationBean.type, "ip-addr")
							)
				,
				(ObjectNode) _mapper.createObjectNode()
					.put(GraphAnnotationBean.label, "host.com")
					.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.vertex.toString())
					.set(GraphAnnotationBean.id, key2
						)
				,
				(ObjectNode) ((ObjectNode) _mapper.createObjectNode()
					.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.edge.toString())
					.put(GraphAnnotationBean.label, "dns-connection")
					.set(GraphAnnotationBean.inV, _mapper.createObjectNode()
													.put(GraphAnnotationBean.name, "host.com")
													.put(GraphAnnotationBean.type, "domain-name")
							))
					.set(GraphAnnotationBean.outV, _mapper.createObjectNode()
													.put(GraphAnnotationBean.name, "1.1.1.1")
													.put(GraphAnnotationBean.type, "ip-addr")
							)
				);
				
		final Map<ObjectNode, Tuple2<List<ObjectNode>, List<ObjectNode>>> ret_val =
				TitanGraphBuildingUtils.groupNewEdgesAndVertices(graph_schema, test_vertices_and_edges.stream());
		
		
		assertEquals(2, ret_val.keySet().size());
		
		final Tuple2<List<ObjectNode>, List<ObjectNode>> ret_val_1 = ret_val.getOrDefault(key1, Tuples._2T(Collections.emptyList(), Collections.emptyList()));		
		final Tuple2<List<ObjectNode>, List<ObjectNode>> ret_val_2 = ret_val.getOrDefault(key2, Tuples._2T(Collections.emptyList(), Collections.emptyList()));
		
		assertEquals(1, ret_val_1._1().size());
		assertEquals("1.1.1.1", ret_val_1._1().get(0).get(GraphAnnotationBean.label).asText());
		assertEquals(1, ret_val_1._2().size());
		assertEquals("dns-connection", ret_val_1._2().get(0).get(GraphAnnotationBean.label).asText());
		assertEquals(1, ret_val_2._1().size());
		assertEquals("host.com", ret_val_2._1().get(0).get(GraphAnnotationBean.label).asText());
		assertEquals(1, ret_val_2._2().size());
		assertEquals("dns-connection", ret_val_2._2().get(0).get(GraphAnnotationBean.label).asText());
	}
	
	@Test
	public void test_finalEdgeGrouping() {
		final TitanGraph titan = getSimpleTitanGraph();
		final TitanTransaction tx = titan.buildTransaction().start();
		
		final Vertex v1 = tx.addVertex("1.1.1.1");
		final Vertex v2 = tx.addVertex("host.com");
		
		final ObjectNode key1 = _mapper.createObjectNode()
				.put(GraphAnnotationBean.name, "1.1.1.1")
				.put(GraphAnnotationBean.type, "ip-addr");

		final ObjectNode key2 = _mapper.createObjectNode()
				.put(GraphAnnotationBean.name, "host.com")
				.put(GraphAnnotationBean.type, "domain-name");

		final List<ObjectNode> mutable_edges = Arrays.asList(
				(ObjectNode) ((ObjectNode) _mapper.createObjectNode()
						.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.edge.toString())
						.put(GraphAnnotationBean.label, "dns-connection")
						.set(GraphAnnotationBean.inV, _mapper.createObjectNode()
														.put(GraphAnnotationBean.name, "host.com")
														.put(GraphAnnotationBean.type, "domain-name")
								))
						.set(GraphAnnotationBean.outV, _mapper.createObjectNode()
														.put(GraphAnnotationBean.name, "1.1.1.1")
														.put(GraphAnnotationBean.type, "ip-addr")
								)
								,
				(ObjectNode) ((ObjectNode) _mapper.createObjectNode()
						.put(GraphAnnotationBean.type, GraphAnnotationBean.ElementType.edge.toString())
						.put(GraphAnnotationBean.label, "self-connect")
						.set(GraphAnnotationBean.inV, _mapper.createObjectNode()
														.put(GraphAnnotationBean.name, "1.1.1.1")
														.put(GraphAnnotationBean.type, "ip-addr")
								))
						.set(GraphAnnotationBean.outV, _mapper.createObjectNode()
														.put(GraphAnnotationBean.name, "1.1.1.1")
														.put(GraphAnnotationBean.type, "ip-addr")
								)
				);

		// Some other handy objects
		final List<ObjectNode> dup_mutable_edges = mutable_edges.stream().map(o -> o.deepCopy()).collect(Collectors.toList());
		final ObjectNode self_link = (ObjectNode) ((ObjectNode) _mapper.createObjectNode().set(GraphAnnotationBean.inV, key1)).set(GraphAnnotationBean.outV, key1);
		final ObjectNode normal_link = (ObjectNode) ((ObjectNode) _mapper.createObjectNode().set(GraphAnnotationBean.inV, key2)).set(GraphAnnotationBean.outV, key1);		
		
		// First pass, should grab the self-connector
		{
			final Map<ObjectNode, List<ObjectNode>> ret_val_pass = TitanGraphBuildingUtils.finalEdgeGrouping(key1, v1, mutable_edges);
			
			// First pass through, snags the edge that points to itself
			final ObjectNode full_group = self_link.deepCopy(); // (at one point had label in here, but that's been removed now to keep the internal data model simpler)
			assertEquals(1, ret_val_pass.size());
			assertEquals(1, ret_val_pass.getOrDefault(full_group, Collections.emptyList()).size());
			final ObjectNode val = ret_val_pass.get(full_group).get(0);
			assertEquals("self-connect", val.get(GraphAnnotationBean.label).asText());
			assertEquals(v1.id(), val.get(GraphAnnotationBean.inV).asLong());
			assertEquals(v1.id(), val.get(GraphAnnotationBean.outV).asLong());
		}
		// Second pass, connects the other edge up
		{
			final Map<ObjectNode, List<ObjectNode>> ret_val_pass = TitanGraphBuildingUtils.finalEdgeGrouping(key2, v2, mutable_edges);
			
			// First pass through, snags the edge that points to itself
			final ObjectNode full_group = normal_link.deepCopy(); // (at one point had label in here, but that's been removed now to keep the internal data model simpler)
			assertEquals(2, ret_val_pass.size());
			assertEquals("Should hav enormal key: " + ret_val_pass.keySet() + " vs " + normal_link, 1, ret_val_pass.getOrDefault(full_group, Collections.emptyList()).size());
			final ObjectNode val = ret_val_pass.get(full_group).get(0);
			assertEquals("dns-connection", val.get(GraphAnnotationBean.label).asText());
			assertEquals(v2.id(), val.get(GraphAnnotationBean.inV).asLong());
			assertEquals(v1.id(), val.get(GraphAnnotationBean.outV).asLong());
		}
		
		// Another first pass, but with the other key so nothing emits
		{
			final Map<ObjectNode, List<ObjectNode>> ret_val_pass = TitanGraphBuildingUtils.finalEdgeGrouping(key2, v2, dup_mutable_edges);
			assertEquals(0, ret_val_pass.size());
		}
		
		tx.commit();
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void test_invokeUserMergeCode() {
		// (Also tests addGraphSON2Graph)
		// (Also tests insertProperties)
		// (Also tests denestProperties)
		
		// This has a massive set of params, here we go:
		final TitanGraph titan = getSimpleTitanGraph();
		TitanManagement mgmt = titan.openManagement();		
		mgmt.makePropertyKey(GraphAnnotationBean.a2_p).dataType(String.class).cardinality(Cardinality.SET).make();
		mgmt.commit();
		final TitanTransaction tx = titan.buildTransaction().start();
		final Vertex v1 = tx.addVertex("existing_1");
		v1.property("existing", true);
		final Vertex v2 = tx.addVertex("existing_2");
		v2.property("existing", true);
		final Edge e1 = v1.addEdge("existing_1_2", v2);
		e1.property("existing", true);
		final Edge e2 = v2.addEdge("existing_2_1", v1);
		e2.property("existing", true);
		// Graph schema
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
					.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
					.with(GraphSchemaBean::custom_finalize_all_objects, false)
				.done().get();
		// Security service
		final MockSecurityService mock_security = new MockSecurityService();
		final String user = "nobody";
		mock_security.setGlobalMockRole("nobody:DataBucketBean:read,write:test:security:*", true);
		// Simple merger
		final IEnrichmentModuleContext delegate_context = Mockito.mock(IEnrichmentModuleContext.class);
		Mockito.when(delegate_context.getNextUnusedId()).thenReturn(0L);
		final Optional<Tuple2<IEnrichmentBatchModule, GraphMergeEnrichmentContext>> maybe_merger = 
				Optional.of(
						Tuples._2T((IEnrichmentBatchModule) new SimpleGraphMergeService(),
									new GraphMergeEnrichmentContext(delegate_context, graph_schema)
						));
		maybe_merger.ifPresent(t2 -> t2._1().onStageInitialize(t2._2(), null, null, null, null));
		// special titan mapper
		final ObjectMapper titan_mapper = titan.io(IoCore.graphson()).mapper().create().createMapper();
		// Bucket
		final String bucket_path = "/test/security";
		// Key
		final ObjectNode vertex_key_1 = _mapper.createObjectNode().put(GraphAnnotationBean.name, "alex").put(GraphAnnotationBean.type, "person"); 
		final ObjectNode vertex_key_2 = _mapper.createObjectNode().put(GraphAnnotationBean.name, "caleb").put(GraphAnnotationBean.type, "person");
		final ObjectNode edge_key = (ObjectNode) ((ObjectNode) _mapper.createObjectNode().set(GraphAnnotationBean.inV, vertex_key_1)).set(GraphAnnotationBean.outV, vertex_key_2);
		// New elements (note can mess about with different keys because they are checked higher up in the stack, here things are just "pre-grouped" lists)
		final List<ObjectNode> new_vertices = Arrays.asList(
				(ObjectNode) ((ObjectNode) _mapper.createObjectNode().put(GraphAnnotationBean.type, "vertex").put(GraphAnnotationBean.label, "test_v_1")
													.set(GraphAnnotationBean.id, vertex_key_1))
													// (set some properties)
													.set(GraphAnnotationBean.properties, ((ObjectNode) _mapper.createObjectNode()
															.put("props_str", "str")
															.set("props_array", _mapper.convertValue(Arrays.asList("a", "r", "r"), JsonNode.class)))
															.set("props_obj", _mapper.createObjectNode().put(GraphAnnotationBean.value, "obj_str"))
															)
				,
				(ObjectNode) ((ObjectNode) _mapper.createObjectNode().put(GraphAnnotationBean.type, "vertex").put(GraphAnnotationBean.label, "test_v_2")
													.set(GraphAnnotationBean.id, vertex_key_2))
				);
		final List<ObjectNode> new_edges = Arrays.asList(
				(ObjectNode) ((ObjectNode) ((ObjectNode) _mapper.createObjectNode().put(GraphAnnotationBean.type, "edge").put(GraphAnnotationBean.label, "test_e_1")
						.set(GraphAnnotationBean.inV, vertex_key_1)).set(GraphAnnotationBean.outV, vertex_key_2))
						// (set some properties)
						.set(GraphAnnotationBean.properties, (ObjectNode) _mapper.createObjectNode()
								.put("props_long", 5L)
								.set("props_obj", _mapper.createObjectNode().set(
										GraphAnnotationBean.value, _mapper.convertValue(Arrays.asList("a", "r", "r"), JsonNode.class)))
								)
				,
				(ObjectNode) ((ObjectNode) _mapper.createObjectNode().put(GraphAnnotationBean.type, "edge").put(GraphAnnotationBean.label, "test_e_2")
						.set(GraphAnnotationBean.inV, vertex_key_1)).set(GraphAnnotationBean.outV, vertex_key_2)								
				);
		// Existing elements
		final List<Tuple2<Vertex, JsonNode>> existing_vertices = Arrays.asList(Tuples._2T(v1, _mapper.createObjectNode()), Tuples._2T(v2, _mapper.createObjectNode()));
		final List<Tuple2<Edge, JsonNode>> existing_edges = Arrays.asList(Tuples._2T(e1, _mapper.createObjectNode()), Tuples._2T(e2, _mapper.createObjectNode()));
		// State
		final Map<JsonNode, Vertex> mutable_existing_vertex_store = new HashMap<>();
		mutable_existing_vertex_store.put(vertex_key_1, v1);
		mutable_existing_vertex_store.put(vertex_key_2, v2);
		final MutableStatsBean mutable_stats = new MutableStatsBean();
		
		// Vertices, no existing elements
		{
			mutable_stats.reset();
			
			List<Vertex> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Vertex.class, bucket_path, vertex_key_1, Arrays.asList(new_vertices.get(0)), Collections.emptyList(), Collections.emptyMap());
			
			assertEquals(1, ret_val.size());
			assertEquals(
					Optionals.streamOf(tx.query().hasNot("existing").vertices(), false).map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList()),
					ret_val.stream().map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList())
					);
			
			ret_val.stream().forEach(v -> 
					assertEquals(Arrays.asList(bucket_path),
							Optionals.streamOf(v.properties(GraphAnnotationBean.a2_p), false).map(p -> p.value().toString()).collect(Collectors.toList())
					)
					);
			
			// Stats
			assertEquals(0L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(0L, mutable_stats.edges_updated);
			assertEquals(0L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(1L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(0L, mutable_stats.vertices_updated);
			
			// Check the properties situation:
			assertEquals(1, Optionals.streamOf(tx.query().has("props_str", "str").vertices(), false).count());
			assertEquals(1, Optionals.streamOf(tx.query().has("props_array", Arrays.asList("a", "r", "r").toArray()).vertices(), false).count());
			assertEquals(1, Optionals.streamOf(tx.query().has("props_obj", "obj_str").vertices(), false).count());
			
			// Check that we did actually add another vertex:
			assertEquals(3, Optionals.streamOf(tx.query().vertices(), false).count());
			// Time
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).vertices(), false).count()); // (added/updated time)
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).vertices(), false).count()); // (added/updated time)
			
			// (clear the graph ready for the next test)
			tx.query().hasNot("existing").vertices().forEach(v -> ((Vertex) v).remove());
		}
		// Vertices, existing elements, multiple new elements
		{
			mutable_stats.reset();
			
			List<Vertex> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Vertex.class, bucket_path, vertex_key_1, new_vertices, existing_vertices, Collections.emptyMap());
			
			assertEquals(1, ret_val.size());
			assertEquals(0, Optionals.streamOf(tx.query().hasNot("existing").vertices(), false).count()); // (since i've overwritten an existing one...)
			assertEquals(
					Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_p).vertices(), false).map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList()),
					ret_val.stream().map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList())
					);
			
			ret_val.stream().forEach(v -> 
					assertEquals(Arrays.asList(bucket_path),
							Optionals.streamOf(v.properties(GraphAnnotationBean.a2_p), false).map(p -> p.value().toString()).collect(Collectors.toList())
					)
					);

			// Stats
			assertEquals(0L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(0L, mutable_stats.edges_updated);
			assertEquals(0L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(1L, mutable_stats.vertices_updated);
			
			// Check that we didn't actually add another vertex:
			assertEquals(2, Optionals.streamOf(tx.query().vertices(), false).count());
			// Time
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).vertices(), false).count()); // (added/updated time)
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).vertices(), false).count()); // (added/updated time)
			
			// (clear the graph ready for the next test)
			tx.query().hasNot("existing").vertices().forEach(v -> ((Vertex) v).remove());
			tx.query().has(GraphAnnotationBean.a2_p).vertices().forEach(v -> ((Vertex) v).properties(GraphAnnotationBean.a2_p).forEachRemaining(p -> p.remove()));
			tx.query().has(GraphAnnotationBean.a2_tc).vertices().forEach(v -> ((Vertex) v).properties(GraphAnnotationBean.a2_tc).forEachRemaining(p -> p.remove()));
			tx.query().has(GraphAnnotationBean.a2_tm).vertices().forEach(v -> ((Vertex) v).properties(GraphAnnotationBean.a2_tm).forEachRemaining(p -> p.remove()));
		}
		// Vertices, existing elements, single element
		{
			mutable_stats.reset();
			
			List<Vertex> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Vertex.class, bucket_path, vertex_key_1, new_vertices, existing_vertices, Collections.emptyMap());
			
			assertEquals(1, ret_val.size());
			assertEquals(0, Optionals.streamOf(tx.query().hasNot("existing").vertices(), false).count()); // (since i've overwritten an existing one...)
			assertEquals(
					Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_p).vertices(), false).map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList()),
					ret_val.stream().map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList())
					);
			
			ret_val.stream().forEach(v -> 
					assertEquals(Arrays.asList(bucket_path),
							Optionals.streamOf(v.properties(GraphAnnotationBean.a2_p), false).map(p -> p.value().toString()).collect(Collectors.toList())
					)
					);

			// Stats
			assertEquals(0L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(0L, mutable_stats.edges_updated);
			assertEquals(0L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(1L, mutable_stats.vertices_updated);
			
			// Check that we didn't actually add another vertex:
			assertEquals(2, Optionals.streamOf(tx.query().vertices(), false).count());
			// Time
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).vertices(), false).count()); // (added/updated time)
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).vertices(), false).count()); // (added/updated time)
			
			// (clear the graph ready for the next test)
			tx.query().hasNot("existing").vertices().forEach(v -> ((Vertex) v).remove());
			tx.query().has(GraphAnnotationBean.a2_p).vertices().forEach(v -> ((Vertex) v).properties(GraphAnnotationBean.a2_p).forEachRemaining(p -> p.remove()));
		}
		// Edges, no existing elements, single new element
		{
			mutable_stats.reset();
			
			List<Edge> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Edge.class, bucket_path, edge_key, Arrays.asList(new_edges.get(0)), Collections.emptyList(), mutable_existing_vertex_store);			
			
			
			assertEquals(1, ret_val.size());
			assertEquals(
					Optionals.streamOf(tx.query().hasNot("existing").edges(), false).map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList()),
					ret_val.stream().map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList())
					);
			
			ret_val.stream().forEach(v -> 
					assertEquals(Arrays.asList(bucket_path),
							Optionals.streamOf(v.properties(GraphAnnotationBean.a2_p), false).map(p -> p.value().toString()).collect(Collectors.toList())
					)
					);

			// Stats
			assertEquals(0L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(1L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(0L, mutable_stats.edges_updated);
			assertEquals(0L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(0L, mutable_stats.vertices_updated);
			
			// Check the properties situation:
			assertEquals(1, Optionals.streamOf(tx.query().has("props_long", 5L).edges(), false).count());
			assertEquals(1, Optionals.streamOf(tx.query().has("props_obj", Arrays.asList("a", "r", "r").toArray()).edges(), false).count());			
			
			// Check that we did actually add another edge:
			assertEquals(3, Optionals.streamOf(tx.query().edges(), false).count());			
			// Time
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).edges(), false).count()); // (added/updated time)
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).edges(), false).count()); // (added/updated time)
			
			// (clear the graph ready for the next test)
			tx.query().hasNot("existing").edges().forEach(v -> ((Edge) v).remove());
		}
		// Edges, no existing elements, multiple new elements
		{
			mutable_stats.reset();
			
			List<Edge> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Edge.class, bucket_path, edge_key, new_edges, Collections.emptyList(), mutable_existing_vertex_store);			
			
			assertEquals(1, ret_val.size());
			assertEquals(
					Optionals.streamOf(tx.query().hasNot("existing").edges(), false).map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList()),
					ret_val.stream().map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList())
					);
			
			ret_val.stream().forEach(v -> 
					assertEquals(Arrays.asList(bucket_path),
							Optionals.streamOf(v.properties(GraphAnnotationBean.a2_p), false).map(p -> p.value().toString()).collect(Collectors.toList())
					)
					);

			// Stats
			assertEquals(0L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(1L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(0L, mutable_stats.edges_updated);
			assertEquals(0L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(0L, mutable_stats.vertices_updated);
			
			// Check that we did actually add another edge:
			assertEquals(3, Optionals.streamOf(tx.query().edges(), false).count());			
			// Time
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).edges(), false).count()); // (added/updated time)
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).edges(), false).count()); // (added/updated time)
			
			// (clear the graph ready for the next test)
			tx.query().hasNot("existing").edges().forEach(v -> ((Edge) v).remove());
		}
		// Edges, existing elements, multiple elements (unlike single one)
		{
			mutable_stats.reset();
			
			List<Edge> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Edge.class, bucket_path, edge_key, new_edges, existing_edges, mutable_existing_vertex_store);			
						
			assertEquals(1, ret_val.size());
			assertEquals(0, Optionals.streamOf(tx.query().hasNot("existing").edges(), false).count()); // (since i've overwritten an existing one...)
			assertEquals(
					Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_p).edges(), false).map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList()),
					ret_val.stream().map(v -> titan_mapper.convertValue(v, JsonNode.class).toString()).collect(Collectors.toList())
					);
			
			ret_val.stream().forEach(v -> 
					assertEquals(Arrays.asList(bucket_path),
							Optionals.streamOf(v.properties(GraphAnnotationBean.a2_p), false).map(p -> p.value().toString()).collect(Collectors.toList())
					)
					);

			// Stats
			assertEquals(0L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(1L, mutable_stats.edges_updated);
			assertEquals(0L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(0L, mutable_stats.vertices_updated);
			
			// Check that we didn't actually add another edge:
			assertEquals(2, Optionals.streamOf(tx.query().edges(), false).count());			
			// Time
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).edges(), false).count()); // (added/updated time)
			assertEquals(1, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).edges(), false).count()); // (added/updated time)
			
			// (clear the graph ready for the next test)
			tx.query().hasNot("existing").edges().forEach(v -> ((Edge) v).remove());
			tx.query().has(GraphAnnotationBean.a2_p).edges().forEach(v -> ((Edge) v).properties(GraphAnnotationBean.a2_p).forEachRemaining(p -> p.remove()));
			tx.query().has(GraphAnnotationBean.a2_tc).edges().forEach(v -> ((Edge) v).properties(GraphAnnotationBean.a2_tc).forEachRemaining(p -> p.remove()));
			tx.query().has(GraphAnnotationBean.a2_tm).edges().forEach(v -> ((Edge) v).properties(GraphAnnotationBean.a2_tm).forEachRemaining(p -> p.remove()));
		}
		// Some error cases:
		// 1) Demonstrate a failure, just remove the label
		{
			mutable_stats.reset();
			
			new_vertices.get(0).remove(GraphAnnotationBean.label);
			
			List<Vertex> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Vertex.class, bucket_path, vertex_key_1, Arrays.asList(new_vertices.get(0)), Collections.emptyList(), Collections.emptyMap());

			// Stats
			assertEquals(0L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(0L, mutable_stats.edges_updated);
			assertEquals(1L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(0L, mutable_stats.vertices_updated);
			
			// Time
			assertEquals(0, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).edges(), false).count()); // (added/updated time)
			assertEquals(0, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).edges(), false).count()); // (added/updated time)
			
			assertEquals(0, ret_val.size());
		}
		// 2) Shouldn't happen, but just check the case where no vertex can be found
		{
			mutable_stats.reset();
			
			List<Edge> ret_val = 
					TitanGraphBuildingUtils.invokeUserMergeCode(
							tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), 
							maybe_merger, titan_mapper, mutable_stats, Edge.class, bucket_path, edge_key, new_edges, Collections.emptyList(), Collections.emptyMap());			
						
			// Stats
			assertEquals(1L, mutable_stats.edge_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.edges_created);
			assertEquals(0L, mutable_stats.edges_emitted);
			assertEquals(0L, mutable_stats.edges_updated);
			assertEquals(0L, mutable_stats.vertex_errors);
			assertEquals(0L, mutable_stats.edge_matches_found);
			assertEquals(0L, mutable_stats.vertices_created);
			assertEquals(0L, mutable_stats.vertices_emitted);
			assertEquals(0L, mutable_stats.vertices_updated);
			
			// Time
			assertEquals(0, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tc).edges(), false).count()); // (added/updated time)
			assertEquals(0, Optionals.streamOf(tx.query().has(GraphAnnotationBean.a2_tm).edges(), false).count()); // (added/updated time)
			
			assertEquals(0, ret_val.size());
		}
		
		tx.commit();
		
		//Quick test coverage of the simple merger, should avoid me having to do any unit testing for that module elsewhere...
		maybe_merger.ifPresent(t2 -> t2._1().onStageComplete(true));
	}
	
	@Test
	public void test_addGraphSON2Graph() {
		
		// No need to do any testing in here, addGraphSON2Graph is adequately tested by the code above
		
//		final String bucket_path = "/test/tagged";
//		// Titan
//		final TitanGraph titan = getSimpleTitanGraph();
//		final TitanTransaction tx = titan.buildTransaction().start();
//		TitanManagement mgmt = titan.openManagement();		
//		mgmt.makePropertyKey(GraphAnnotationBean.a2_p).dataType(String.class).cardinality(Cardinality.SET).make();
//		mgmt.commit();
//		// Key
//		final ObjectNode key = _mapper.createObjectNode().put(GraphAnnotationBean.name, "alex").put(GraphAnnotationBean.type, "person"); 
//		// State
//		final Map<JsonNode, Vertex> mutable_existing_vertex_store = new HashMap<>();
//		// GraphSON to add:
//		final ObjectNode vertex_to_add = _mapper.createObjectNode().put(GraphAnnotationBean.label, "test_vertex");
//		final ObjectNode edge_to_add = _mapper.createObjectNode().put(GraphAnnotationBean.label, "test_edge");
//		
//		{
//			@SuppressWarnings("unused")
//			Validation<BasicMessageBean, Vertex> ret_val = 
//					TitanGraphBuildingUtils.addGraphSON2Graph(bucket_path, key, vertex_to_add, mutable_existing_vertex_store, tx, Vertex.class);
//		}
//		{
//			@SuppressWarnings("unused")
//			Validation<BasicMessageBean, Edge> ret_val = 
//					TitanGraphBuildingUtils.addGraphSON2Graph(bucket_path, key, edge_to_add, mutable_existing_vertex_store, tx, Edge.class);
//		}
//		
//		tx.commit();
	}

	//TODO: have a check for if security not set .. vertices: done, NEED EDGES TEST THOUGH 
	
	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////
	////////////////////////////////
	
	// UTILS - TOP LEVEL LOGIC

	@Test
	public void test_buildGraph_getUserGeneratedAssets() {
		test_buildGraph_getUserGeneratedAssets_run();
	};
	public List<ObjectNode> test_buildGraph_getUserGeneratedAssets_run() {
		
		// (this is a pretty simple method so will use to test the decomposing service also)
		
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
					.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
				.done().get();
		
		final IEnrichmentModuleContext delegate_context = Mockito.mock(IEnrichmentModuleContext.class);
		Mockito.when(delegate_context.getNextUnusedId()).thenReturn(0L);
		final Optional<Tuple2<IEnrichmentBatchModule, GraphDecompEnrichmentContext>> maybe_decomposer = 
				Optional.of(
						Tuples._2T((IEnrichmentBatchModule) new SimpleGraphDecompService(),
									new GraphDecompEnrichmentContext(delegate_context, graph_schema)
						));

		final EnrichmentControlMetadataBean control = BeanTemplateUtils.build(EnrichmentControlMetadataBean.class)
					.with(EnrichmentControlMetadataBean::config,
							BeanTemplateUtils.toMap(
							BeanTemplateUtils.build(SimpleDecompConfigBean.class)
								.with(SimpleDecompConfigBean::elements, Arrays.asList(
										BeanTemplateUtils.build(SimpleDecompElementBean.class)
											.with(SimpleDecompElementBean::edge_name, "test_edge_1")
											.with(SimpleDecompElementBean::from_fields, Arrays.asList("int_ip1", "int_ip2"))
											.with(SimpleDecompElementBean::from_type, "ip")
											.with(SimpleDecompElementBean::to_fields, Arrays.asList("host1", "host2"))
											.with(SimpleDecompElementBean::to_type, "host")
										.done().get()
										,
										BeanTemplateUtils.build(SimpleDecompElementBean.class)
											.with(SimpleDecompElementBean::edge_name, "test_edge_2")
											.with(SimpleDecompElementBean::from_fields, Arrays.asList("missing"))
											.with(SimpleDecompElementBean::from_type, "ip")
											.with(SimpleDecompElementBean::to_fields, Arrays.asList("host1", "host2"))
											.with(SimpleDecompElementBean::to_type, "host")
										.done().get()
										)
								)
							.done().get())
					)
				.done().get();
		
		final Stream<Tuple2<Long, IBatchRecord>> batch = 
				Stream.<ObjectNode>of(
					_mapper.createObjectNode().put("int_ip1", "ipA").put("int_ip2", "ipB").put("host1", "dX").put("host2", "dY")
					,
					_mapper.createObjectNode().put("int_ip1", "ipA").put("host1", "dZ").put("host2", "dY")
				)
				.map(o -> Tuples._2T(0L, new BatchRecordUtils.JsonBatchRecord(o)))
				;		
		
		maybe_decomposer.ifPresent(t2 -> t2._1().onStageInitialize(t2._2(), null, control, null, null));
		
		final List<ObjectNode> ret_val = TitanGraphBuildingUtils.buildGraph_getUserGeneratedAssets(
				batch, Optional.empty(), Optional.empty(), maybe_decomposer
				);
		
		// test_buildGraph_collectUserGeneratedAssets needs
		// Nodes
		// IPA, IPB, DX, DY, DZ
		// Links
		// IPA: IPA->DX, IPA->DY, IPA->DZ
		// IPB: IPB->DX, IPB->DY
		// DX: IPA, IPB
		// DY: IPA, IPB
		// DZ: IPA
		
		assertEquals(11, ret_val.size()); // (get more vertices because they are generated multiple times...)
		assertEquals(5, ret_val.stream().filter(v -> GraphAnnotationBean.ElementType.vertex.toString().equals(v.get(GraphAnnotationBean.type).asText())).count());
		assertEquals(6, ret_val.stream().filter(v -> GraphAnnotationBean.ElementType.edge.toString().equals(v.get(GraphAnnotationBean.type).asText())).count());
		
		//coverage!
		maybe_decomposer.ifPresent(t2 -> t2._1().onStageComplete(true));
		
		return ret_val;
	}
	
	@Test
	public void test_buildGraph_collectUserGeneratedAssets() {
		// Titan
		final TitanGraph titan = getSimpleTitanGraph();
		final TitanTransaction tx = titan.buildTransaction().start();
		// (add vertices and edges later)
		
		// Graph schema
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
				.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
			.done().get();
		// Security service
		final MockSecurityService mock_security = new MockSecurityService();
		final String user = "nobody";
		mock_security.setGlobalMockRole("nobody:DataBucketBean:read,write:test:security:*", true);
		// Bucket
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, "/test/security")
				.done().get();
		
		// Stream of objects		
		final List<ObjectNode> vertices_and_edges = test_buildGraph_getUserGeneratedAssets_run();
				
		// Empty graph, collect information
		{
			final Stream<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val_s = 
					TitanGraphBuildingUtils.buildGraph_collectUserGeneratedAssets(tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), bucket, vertices_and_edges.stream())
					;
			
			final List<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val = ret_val_s.collect(Collectors.toList());
			
			// (5 different vertex keys)
			assertEquals(5, ret_val.size()); 
			// (no existing elements)
			assertTrue(ret_val.stream().allMatch(t4 -> t4._4().isEmpty()));
			
			// Nodes
			// IPA, IPB, DX, DY, DZ
			// Links
			// IPA: IPA->DX, IPA->DY, IPA->DZ
			// IPB: IPB->DX, IPB->DY
			// DX: IPA, IPB
			// DY: IPA, IPB
			// DZ: IPA
			
			// (note there can be dups in here, so do the distinct)
			final Function<String, Long> getDistinctCount = key -> {
				return ret_val.stream().filter(t4 -> t4._1().get(GraphAnnotationBean.name).asText().equals(key)).map(t4 -> t4._3().stream().distinct().count()).reduce((a, b) -> a+b).orElse(0L);
			};
			assertEquals(3, getDistinctCount.apply("ipA").intValue());
			assertEquals(2, getDistinctCount.apply("ipB").intValue());
			assertEquals(2, getDistinctCount.apply("dX").intValue());
			assertEquals(2, getDistinctCount.apply("dY").intValue());
			assertEquals(1, getDistinctCount.apply("dZ").intValue());
		}
		// Add some nodes and rerun
		{
			Vertex v1 = tx.addVertex("existing_ipA");
			v1.property(GraphAnnotationBean.name, "ipA");
			v1.property(GraphAnnotationBean.type, "ip");
			v1.property(GraphAnnotationBean.a2_p, "/test/security");
			Vertex v2 = tx.addVertex("existing_dY");
			v2.property(GraphAnnotationBean.name, "dY");
			v2.property(GraphAnnotationBean.type, "host");
			v2.property(GraphAnnotationBean.a2_p, "/test/security");
			
			final Stream<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val_s = 
					TitanGraphBuildingUtils.buildGraph_collectUserGeneratedAssets(tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), bucket, vertices_and_edges.stream())
					;
			
			final List<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val = ret_val_s.collect(Collectors.toList());
			
			// (5 different vertex keys)
			assertEquals(5, ret_val.size()); 
			
			// (note there can be dups in here, so do the distinct)
			final Function<String, Long> getDistinctCount = key -> {
				return ret_val.stream().filter(t4 -> t4._1().get(GraphAnnotationBean.name).asText().equals(key)).map(t4 -> t4._3().stream().distinct().count()).reduce((a, b) -> a+b).orElse(0L);
			};
			assertEquals(3, getDistinctCount.apply("ipA").intValue());
			assertEquals(2, getDistinctCount.apply("ipB").intValue());
			assertEquals(2, getDistinctCount.apply("dX").intValue());
			assertEquals(2, getDistinctCount.apply("dY").intValue());
			assertEquals(1, getDistinctCount.apply("dZ").intValue());
			final Function<String, Long> getDistinctExistingCount = key -> {
				return ret_val.stream().filter(t4 -> t4._1().get(GraphAnnotationBean.name).asText().equals(key)).map(t4 -> t4._4().stream().distinct().count()).reduce((a, b) -> a+b).orElse(0L);
			};
			assertEquals(1, getDistinctExistingCount.apply("ipA").intValue());
			assertEquals(0, getDistinctExistingCount.apply("ipB").intValue());
			assertEquals(0, getDistinctExistingCount.apply("dX").intValue());
			assertEquals(1, getDistinctExistingCount.apply("dY").intValue());
			assertEquals(0, getDistinctExistingCount.apply("dZ").intValue());
		}
		// Test bucket - check that treats like empty
		{
			final DataBucketBean test_bucket = BucketUtils.convertDataBucketBeanToTest(bucket, "nobody");
			
			final Stream<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val_s = 
					TitanGraphBuildingUtils.buildGraph_collectUserGeneratedAssets(tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), test_bucket, vertices_and_edges.stream())
					;
			
			final List<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val = ret_val_s.collect(Collectors.toList());
			
			// (5 different vertex keys)
			assertEquals(5, ret_val.size()); 
			// (no existing elements)
			assertTrue(ret_val.stream().allMatch(t4 -> t4._4().isEmpty()));
			
			// Nodes
			// IPA, IPB, DX, DY, DZ
			// Links
			// IPA: IPA->DX, IPA->DY, IPA->DZ
			// IPB: IPB->DX, IPB->DY
			// DX: IPA, IPB
			// DY: IPA, IPB
			// DZ: IPA
			
			// (note there can be dups in here, so do the distinct)
			final Function<String, Long> getDistinctCount = key -> {
				return ret_val.stream().filter(t4 -> t4._1().get(GraphAnnotationBean.name).asText().equals(key)).map(t4 -> t4._3().stream().distinct().count()).reduce((a, b) -> a+b).orElse(0L);
			};
			assertEquals(3, getDistinctCount.apply("ipA").intValue());
			assertEquals(2, getDistinctCount.apply("ipB").intValue());
			assertEquals(2, getDistinctCount.apply("dX").intValue());
			assertEquals(2, getDistinctCount.apply("dY").intValue());
			assertEquals(1, getDistinctCount.apply("dZ").intValue());
		}
		// Remove security - check that treats like empty
		{
			mock_security.setGlobalMockRole("nobody:DataBucketBean:read,write:test:security:*", false);
			
			final Stream<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val_s = 
					TitanGraphBuildingUtils.buildGraph_collectUserGeneratedAssets(tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), bucket, vertices_and_edges.stream())
					;
			
			final List<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> ret_val = ret_val_s.collect(Collectors.toList());
			
			// (5 different vertex keys)
			assertEquals(5, ret_val.size()); 
			// (no existing elements)
			assertTrue(ret_val.stream().allMatch(t4 -> t4._4().isEmpty()));
			
			// Nodes
			// IPA, IPB, DX, DY, DZ
			// Links
			// IPA: IPA->DX, IPA->DY, IPA->DZ
			// IPB: IPB->DX, IPB->DY
			// DX: IPA, IPB
			// DY: IPA, IPB
			// DZ: IPA
			
			// (note there can be dups in here, so do the distinct)
			final Function<String, Long> getDistinctCount = key -> {
				return ret_val.stream().filter(t4 -> t4._1().get(GraphAnnotationBean.name).asText().equals(key)).map(t4 -> t4._3().stream().distinct().count()).reduce((a, b) -> a+b).orElse(0L);
			};
			assertEquals(3, getDistinctCount.apply("ipA").intValue());
			assertEquals(2, getDistinctCount.apply("ipB").intValue());
			assertEquals(2, getDistinctCount.apply("dX").intValue());
			assertEquals(2, getDistinctCount.apply("dY").intValue());
			assertEquals(1, getDistinctCount.apply("dZ").intValue());
		}
		
		tx.commit();
	}
	
	@Test
	public void test_buildGraph_handleMerge() {
		// Titan
		final TitanGraph titan = getSimpleTitanGraph();
		final TitanTransaction tx = titan.buildTransaction().start();
		// Graph schema
		final GraphSchemaBean graph_schema = BeanTemplateUtils.build(GraphSchemaBean.class)
				.with(GraphSchemaBean::deduplication_fields, Arrays.asList(GraphAnnotationBean.name, GraphAnnotationBean.type))
			.done().get();
		// Security service
		final MockSecurityService mock_security = new MockSecurityService();
		final String user = "nobody";
		//TODO: set permission
		// Bucket
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, "/test/security")
				//TODO: probably need something else as well?
				.done().get();
		// Simple merger
		final Optional<Tuple2<IEnrichmentBatchModule, GraphMergeEnrichmentContext>> maybe_merger = 
				Optional.of(
						Tuples._2T((IEnrichmentBatchModule) new SimpleGraphMergeService(),
									new GraphMergeEnrichmentContext(null, graph_schema)
						));
		maybe_merger.ifPresent(t2 -> t2._1().onStageInitialize(t2._2(), null, null, null, null));
		// Input TODO
		final Stream<Tuple4<ObjectNode, List<ObjectNode>, List<ObjectNode>, List<Tuple2<Vertex, JsonNode>>>> mergeable = Stream.empty();
		
		final MutableStatsBean mutable_stats = new MutableStatsBean();		
		
		{
			TitanGraphBuildingUtils.buildGraph_handleMerge(tx, graph_schema, Tuples._2T(user, mock_security), Optional.empty(), mutable_stats, maybe_merger, bucket, mergeable);
			//TODO		
		}
	}
	
	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////
	////////////////////////////////
	
	// (Test utilities)
	
	protected TitanGraph getSimpleTitanGraph() {
		final TitanGraph titan = TitanFactory.build().set("storage.backend", "inmemory").open();
		return titan;		
	}
}
