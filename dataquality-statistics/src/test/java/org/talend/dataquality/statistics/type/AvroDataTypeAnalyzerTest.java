package org.talend.dataquality.statistics.type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.talend.dataquality.common.inference.AvroQualityAnalyzer.GLOBAL_QUALITY_PROP_NAME;
import static org.talend.dataquality.statistics.type.AvroDataTypeAnalyzer.DATA_TYPE_AGGREGATE;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.talend.dataquality.common.util.AvroUtils;

public class AvroDataTypeAnalyzerTest {

    private AvroDataTypeAnalyzer analyzer;

    private Schema personSchema;

    private DecoderFactory decoderFactory = new DecoderFactory();

    private GenericRecord createFromJson(String jsonRecord) throws IOException {
        Decoder decoder = decoderFactory.jsonDecoder(personSchema, jsonRecord);
        DatumReader<GenericData.Record> reader = new GenericDatumReader<>(personSchema);
        GenericRecord record = reader.read(null, decoder);

        return record;
    }

    private GenericRecord loadPerson(String filename) {
        try {
            byte[] json = Files.readAllBytes(Paths.get(getClass().getResource("/avro/" + filename + ".json").toURI()));
            return createFromJson(new String(json));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private GenericRecord[] loadPersons(String... filenames) {
        return Arrays.asList(filenames).stream().map(filename -> loadPerson(filename)).toArray(GenericRecord[]::new);
    }

    @Before
    public void setUp() throws URISyntaxException, IOException {
        byte[] avsc = Files.readAllBytes(Paths.get(getClass().getResource("/avro/person.avsc").toURI()));
        personSchema = new Schema.Parser().parse(new String(avsc));
        analyzer = new AvroDataTypeAnalyzer();
        analyzer.init(personSchema);
    }

    @After
    public void tearDown() {
        analyzer.end();
    }

    @Test
    public void testNull() {
        analyzer.analyze((IndexedRecord) null);

        Schema result = analyzer.getResult();
        assertNotNull(result);

        Map<String, Long> prop = (Map) result.getObjectProp(GLOBAL_QUALITY_PROP_NAME);
        // checkQuality(prop, 0, 0, 0, 0);
    }

    @Test
    public void testGlobalDataType() {
        GenericRecord[] records = loadPersons("alice", "bob", "charlie");

        for (GenericRecord record : records) {
            analyzer.analyze(record);
        }

        Schema result = analyzer.getResult();
        assertNotNull(result);

        List<Map<String, Object>> aggregations =
                (List<Map<String, Object>>) result.getField("birthdate").schema().getObjectProp("dataTypeAggregate");

        assertEquals(3l, aggregations.get(0).get("total"));
        assertEquals(DataTypeEnum.DATE.toString(), aggregations.get(0).get("dataType"));
    }

    @Test
    public void testSimpleFields() throws IOException, URISyntaxException {
        GenericRecord[] persons = loadPersons("alice");
        Stream<IndexedRecord> records = Arrays.stream(persons);
        Iterator<IndexedRecord> outRecords = analyzer.analyze(records).iterator();

        // Check the output records
        int count = 0;
        while (outRecords.hasNext()) {
            GenericRecord out = (GenericRecord) outRecords.next();
            GenericData.Record firstnameRecord = (GenericData.Record) out.get("firstname");
            DataTypeEnum dataType = (DataTypeEnum) firstnameRecord.get("dataType");
            assertEquals(DataTypeEnum.STRING, dataType);
            count++;
        }
        assertEquals(persons.length, count);

        Schema result = analyzer.getResult();
        assertNotNull(result);

        List<Map<String, Object>> aggregation =
                (List<Map<String, Object>>) result.getField("birthdate").schema().getObjectProp("dataTypeAggregate");
        assertEquals(DataTypeEnum.DATE.toString(), aggregation.get(0).get("dataType"));
    }

    @Test
    public void testUnion() throws IOException, URISyntaxException {
        GenericRecord[] records = loadPersons("alice", "bob", "charlie");
        List<IndexedRecord> outRecords = analyzer.analyze(Arrays.stream(records)).collect(Collectors.toList());

        Schema result = analyzer.getResult();
        assertNotNull(result);

        Schema zipcodeSchema = result.getField("location").schema().getField("zipcode").schema();

        Schema specificZipcodeSchema =
                zipcodeSchema.getTypes().stream().filter(s -> s.getType() == Schema.Type.STRING).findFirst().get();
        List<Map<String, Object>> prop =
                (List<Map<String, Object>>) specificZipcodeSchema.getObjectProp(DATA_TYPE_AGGREGATE);
        assertEquals(1l, prop.get(0).get("total"));

        specificZipcodeSchema =
                zipcodeSchema.getTypes().stream().filter(s -> s.getType() == Schema.Type.NULL).findFirst().get();
        prop = (List<Map<String, Object>>) specificZipcodeSchema.getObjectProp(DATA_TYPE_AGGREGATE);
        assertEquals(0, prop.size());

        specificZipcodeSchema =
                zipcodeSchema.getTypes().stream().filter(s -> s.getType() == Schema.Type.RECORD).findFirst().get();
        Schema codeSchema = specificZipcodeSchema.getField("code").schema();
        prop = (List<Map<String, Object>>) codeSchema.getObjectProp(DATA_TYPE_AGGREGATE);
        assertEquals(1l, prop.get(0).get("total"));
    }

    @Test
    public void testSemanticSchemaNotMatchingRecord() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/date.avro").getPath();
            File dateAvroFile = new File(path);
            DataFileReader<GenericRecord> dateAvroReader =
                    new DataFileReader<>(dateAvroFile, new GenericDatumReader<>());
            analyzer.analyze(dateAvroReader.next());
            Schema result = analyzer.getResult();
            assertNotNull(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOnSwitch() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/Switch.avro").getPath();
            Pair<Stream<IndexedRecord>, Schema> pair = AvroUtils.streamAvroFile(new File(path));
            analyzer.init(pair.getRight());
            List<IndexedRecord> results = analyzer.analyze(pair.getLeft()).collect(Collectors.toList());
            Schema result = analyzer.getResult();
            assertNotNull(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOnNoFancy() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/no-fancy-structures-10.avro").getPath();
            File fileEntry = new File(path);
            DataFileReader<GenericRecord> dateAvroReader = new DataFileReader<>(fileEntry, new GenericDatumReader<>());
            analyzer.init(dateAvroReader.getSchema());

            dateAvroReader.forEach(analyzer::analyze);

            Schema result = analyzer.getResult();
            assertNotNull(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOnExample2() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/example2.avro").getPath();
            File fileEntry = new File(path);
            DataFileReader<GenericRecord> dateAvroReader = new DataFileReader<>(fileEntry, new GenericDatumReader<>());
            analyzer.init(dateAvroReader.getSchema());

            dateAvroReader.forEach(analyzer::analyze);

            Schema result = analyzer.getResult();
            assertNotNull(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOn96() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/96.avro").getPath();
            File fileEntry = new File(path);
            DataFileReader<GenericRecord> dateAvroReader = new DataFileReader<>(fileEntry, new GenericDatumReader<>());
            analyzer.init(dateAvroReader.getSchema());
            dateAvroReader.forEach(analyzer::analyze);
            Schema result = analyzer.getResult();
            assertNotNull(
                    result.getField("friends").schema().getElementType().getField("name").getProp(DATA_TYPE_AGGREGATE));
            assertNotNull(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOnBigBusiness() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/big_business.avro").getPath();
            File fileEntry = new File(path);
            DataFileReader<GenericRecord> dateAvroReader = new DataFileReader<>(fileEntry, new GenericDatumReader<>());
            analyzer.init(dateAvroReader.getSchema());
            dateAvroReader.forEach(analyzer::analyze);
            Schema result = analyzer.getResult();

            List<Map<String, Object>> prop = (List<Map<String, Object>>) result
                    .getField("business_id")
                    .schema()
                    .getTypes()
                    .get(1)
                    .getObjectProp(DATA_TYPE_AGGREGATE);
            assertEquals(1000l, prop.get(0).get("total"));

            prop = (List<Map<String, Object>>) result
                    .getField("business")
                    .schema()
                    .getTypes()
                    .get(1)
                    .getField("location")
                    .schema()
                    .getTypes()
                    .get(1)
                    .getField("taxReturnsFiled")
                    .schema()
                    .getTypes()
                    .get(1)
                    .getObjectProp(DATA_TYPE_AGGREGATE);
            assertEquals(682l, prop.get(0).get("total"));
            assertEquals("INTEGER", prop.get(0).get("dataType"));
            assertEquals(318l, prop.get(1).get("total"));
            assertEquals("EMPTY", prop.get(1).get("dataType"));
            assertNotNull(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOnComplexSchemas() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/complex").getPath();
            File primitiveFolder = new File(path);
            for (final File fileEntry : Objects.requireNonNull(primitiveFolder.listFiles())) {
                DataFileReader<GenericRecord> dateAvroReader =
                        new DataFileReader<>(fileEntry, new GenericDatumReader<>());
                analyzer.init(dateAvroReader.getSchema());

                dateAvroReader.forEach(analyzer::analyze);

                Schema result = analyzer.getResult();
                assertNotNull(result);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOnPrimitiveSchemas() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/primitive").getPath();
            File primitiveFolder = new File(path);
            for (final File fileEntry : Objects.requireNonNull(primitiveFolder.listFiles())) {
                DataFileReader<GenericRecord> dateAvroReader =
                        new DataFileReader<>(fileEntry, new GenericDatumReader<>());
                analyzer.init(dateAvroReader.getSchema());

                dateAvroReader.forEach(analyzer::analyze);

                Schema result = analyzer.getResult();
                assertNotNull(result);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAvroDataTypeAnalyzerOnStructureSchemas() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample/structure").getPath();
            File primitiveFolder = new File(path);
            for (final File fileEntry : Objects.requireNonNull(primitiveFolder.listFiles())) {
                DataFileReader<GenericRecord> dateAvroReader =
                        new DataFileReader<>(fileEntry, new GenericDatumReader<>());
                analyzer.init(dateAvroReader.getSchema());

                dateAvroReader.forEach(analyzer::analyze);

                Schema result = analyzer.getResult();
                assertNotNull(result);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    @Ignore
    public void testAvroDataTypeAnalyzerOnBigSamples() {
        try {
            String path = AvroDataTypeAnalyzerTest.class.getResource("../sample").getPath();
            File primitiveFolder = new File(path);
            for (final File fileEntry : Objects.requireNonNull(primitiveFolder.listFiles())) {
                if (fileEntry.isFile()) {
                    System.out.println("Analyzing  " + fileEntry);
                    DataFileReader<GenericRecord> dateAvroReader =
                            new DataFileReader<>(fileEntry, new GenericDatumReader<>());
                    analyzer.init(dateAvroReader.getSchema());

                    dateAvroReader.forEach(analyzer::analyze);

                    Schema result = analyzer.getResult();
                    assertNotNull(result);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}