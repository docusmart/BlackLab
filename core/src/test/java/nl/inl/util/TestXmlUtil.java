/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.util;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.junit.Assert;
import org.junit.Test;

import java.io.StringWriter;

public class TestXmlUtil {

    @Test
    public void testXmlToPlainText() {
        // Remove tags
        Assert.assertEquals("test test test", XmlUtil.xmlToPlainText("test <bla>test</bla> test"));

        // Interpret entities
        Assert.assertEquals("test > test", XmlUtil.xmlToPlainText("test &gt; test"));

        // Interpret numerical entities
        Assert.assertEquals("test A test", XmlUtil.xmlToPlainText("test &#65; test"));

        // Interpret hex numerical entities
        Assert.assertEquals("test B test", XmlUtil.xmlToPlainText("test &#x42; test"));

        // Ignore entities inside tags
        Assert.assertEquals("test test", XmlUtil.xmlToPlainText("test <bla test=\"&quot;\" > test"));

        // Other whitespace characters normalized to space
        Assert.assertEquals("test test", XmlUtil.xmlToPlainText("test\ntest"));

        // Normalize whitespace; keep leading space
        Assert.assertEquals(" test test", XmlUtil.xmlToPlainText("\t\ttest \n\rtest"));

        // Replace with non-breaking spaces
        Assert.assertEquals("test\u00A0test", XmlUtil.xmlToPlainText("test test", true));

        // Replace with non-breaking spaces; keep trailing space
        Assert.assertEquals("test\u00A0test\u00A0", XmlUtil.xmlToPlainText("test test ", true));
    }

    @Test(expected = JsonParseException.class)
    public void testYamlMultiLineNotCanonical() throws Exception {
        String key = "SomeKey:\n\nA";
        ObjectMapper jsonMapper = Json.getJsonObjectMapper();
        ObjectNode jsonRoot = jsonMapper.createObjectNode();
        jsonRoot.put(key, 302);

        YAMLFactory yamlFactory = new YAMLFactory();
        ObjectMapper yamlObjectMapper = new ObjectMapper(yamlFactory);
        yamlObjectMapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        StringWriter swriter = new StringWriter();

        yamlObjectMapper.writeValue(swriter, jsonRoot);

        ObjectMapper readMapper =  Json.getYamlObjectMapper();
        ObjectNode readJsonRoot = (ObjectNode) readMapper.readTree(swriter.toString());
        Assert.assertEquals(readJsonRoot.get(key).asInt(),302 );
    }

    @Test
    public void testYamlMultiLineWithCanonical() throws Exception {
        String key = "SomeKey:\n\nA";
        ObjectMapper jsonMapper = Json.getJsonObjectMapper();
        ObjectNode jsonRoot = jsonMapper.createObjectNode();
        jsonRoot.put(key, 302);

        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.configure(YAMLGenerator.Feature.CANONICAL_OUTPUT, true);
        ObjectMapper yamlObjectMapper = new ObjectMapper(yamlFactory);
        yamlObjectMapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        StringWriter swriter = new StringWriter();

        yamlObjectMapper.writeValue(swriter, jsonRoot);

        ObjectMapper readMapper =  Json.getYamlObjectMapper();
        ObjectNode readJsonRoot = (ObjectNode) readMapper.readTree(swriter.toString());
        Assert.assertEquals(readJsonRoot.get(key).asInt(),302 );
    }


}
