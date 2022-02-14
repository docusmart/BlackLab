const chai = require("chai");
const chaiHttp = require("chai-http");
const expect = chai.expect;
const should = chai.should();
const assert = chai.assert;
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const parseXmlString = require('xml2js').parseStringPromise;
chai.use(chaiHttp);

const constants = require('./constants');
const SERVER_URL = constants.SERVER_URL;

const TEST_DATA_ROOT =  '../private';

const INPUT_FORMAT_NAME = "test-input-format";
const INPUT_FORMAT_PATH = path.resolve(__dirname, TEST_DATA_ROOT, INPUT_FORMAT_NAME + ".blf.yml");

const DOC_TO_INDEX = "documents-to-index.xml";
const DOC_TO_INDEX_PATH = path.resolve(__dirname, TEST_DATA_ROOT, DOC_TO_INDEX);

const EXPECTED_INDEX_CONTENT = "expected-index-content.json";
const EXPECTED_INDEX_CONTENT_PATH = path.resolve(__dirname, TEST_DATA_ROOT, EXPECTED_INDEX_CONTENT);

const EXPECTED_INDEX_METADATA = "expected-index-metadata.json";
const EXPECTED_INDEX_METADATA_PATH = path.resolve(__dirname, TEST_DATA_ROOT, EXPECTED_INDEX_METADATA);

const EXPECTED_QUERY_PATH = path.resolve(__dirname, TEST_DATA_ROOT, 'expected-query-result.xml');


function addDefaultHeaders(request) {
    request.auth(constants.BLACKLAB_USER, constants.BLACKLAB_PASSWORD)
    let allHeaders = {
        'X-Request-ID': crypto.randomBytes(8).toString('hex'),
    }

    for (let key in allHeaders) {
        request.set(key, allHeaders[key]);
    }
}

function createIndexName() {
    indexName = "test-index-" + crypto.randomInt(10000).toString();
    return indexName;
}

async function createInputFormat(){
    let request = chai
        .request(SERVER_URL)
        .post('/input-formats')
        .set('Accept', 'application/json')
        .attach('data', fs.readFileSync(INPUT_FORMAT_PATH), 'format.yml');
    addDefaultHeaders(request);
    return request;
}

async function createIndex(indexName) {
    index_url = constants.BLACKLAB_USER + ":" + indexName
    let request = chai
        .request(SERVER_URL)
        .post('/')
        .query({
            'name': index_url,
            'display': indexName,
            'format': constants.BLACKLAB_USER + ":" + INPUT_FORMAT_NAME
        })
        .set('Accept', 'application/json')
    addDefaultHeaders(request);
    return request.send();
}

async function getIndexRequest(indexName) {
    indexUrl = constants.BLACKLAB_USER + ":" + indexName
    let request = chai
        .request(SERVER_URL)
        .get("/" + indexUrl + "/status")
        .set('Accept', 'application/json')
    addDefaultHeaders(request);
    return request.send();
}

async function addToIndex(indexName, payloadPath) {
    indexUrl = constants.BLACKLAB_USER + ":" + indexName
    let request = chai
        .request(SERVER_URL)
        .post('/' + indexUrl + '/docs')
        .set('Accept', 'application/json')
        .attach('data', fs.readFileSync(payloadPath), 'testdocs' );
    addDefaultHeaders(request);
    return request;
}
async function getIndexContent(indexName) {
    indexUrl = constants.BLACKLAB_USER + ":" + indexName
    let request = chai
        .request(SERVER_URL)
        .get("/" + indexUrl + "/docs")
        .set('Accept', 'application/json')
    addDefaultHeaders(request);
    return request.send();
}

function clearKeys(keys, data) {
    var diff = Object.keys(data).filter(k => !keys.includes(k));
    var cleanedData = {}
    for (let k of diff) {
        var value = data[k];
        if (!(value instanceof Array)) {
            cleanedData[k] = value;
            continue;
        }
        if (value.length === 1 && typeof value[0] === "string" && (value[0].trim() === "" || value[0].trim() === "\n")) {
            cleanedData[k] = [];
        } else {
            cleanedData[k] = value;
        }
    }
    return cleanedData
}

async function getIndexMetadata(indexName) {
    indexUrl = constants.BLACKLAB_USER + ":" + indexName
    let request = chai
        .request(SERVER_URL)
        .get("/" + indexUrl + "/")
        .set('Accept', 'application/json')
    addDefaultHeaders(request);
    return request.send();
}

async function toJson(xml) {
    return parseXmlString(xml);
}

function queryIndex(indexName, pattern, filters, format = 'application/json') {
    indexUrl = constants.BLACKLAB_USER + ":" + indexName + "/hits";
    var respFormat = format === "" ? "application/json" : format;
    let request = chai
        .request(SERVER_URL)
        .post("/" + indexUrl + "/")
        .buffer()
        .set('Accept', respFormat)
    if (filters !== "") {
        request.query({"patt": pattern, "filter": filters})
    } else {
        request.query({"patt": pattern})
    }
    addDefaultHeaders(request);
    request.parse((res, callback) => {
        res.text = '';
        res.on('data', (chk) => {
            res.text += chk;
        });
        res.on('end', () => callback(null, res.text));
    });
    return request.send()
}


describe('Indexing tests', () => {
    it('create a new index', async () => {
        indexName = createIndexName();
        let respFormat = await createInputFormat();
        assert.isTrue(respFormat.ok);

        let createRes = await createIndex(indexName);
        assert.isTrue(createRes.ok);

        let resGetIndex = await getIndexRequest(indexName);
        assert.isTrue(resGetIndex.ok);
    });
    it('adds to index', async () => {
        indexName = createIndexName();
        await createInputFormat();

        let createRes = await createIndex(indexName);
        assert.isTrue(createRes.ok);

        let addReq = await addToIndex(indexName, DOC_TO_INDEX_PATH);
        assert.isTrue(addReq.ok);
        
        let indexContents = await getIndexContent(indexName);
        assert.isTrue(indexContents.ok);

        var body = indexContents.body;
        var expectedContent = JSON.parse(fs.readFileSync(EXPECTED_INDEX_CONTENT_PATH));

        var keys = ['summary', 'searchTime'];
        expect(clearKeys(keys, expectedContent)).to.be.deep.equal(clearKeys(keys, body));
    });

    it('get index metadata', async () => {
        indexName = createIndexName();
        await createInputFormat();

        let createRes = await createIndex(indexName);
        assert.isTrue(createRes.ok);

        let addReq = await addToIndex(indexName, DOC_TO_INDEX_PATH);
        assert.isTrue(addReq.ok);

        let indexMetadata = await getIndexMetadata(indexName);
        var body = indexMetadata.body;
        assert.isTrue(indexMetadata.ok);

        var expectedMetadata = JSON.parse(fs.readFileSync(EXPECTED_INDEX_METADATA_PATH));

        var keys = ['indexName', 'displayName', 'versionInfo']
        expect(clearKeys(keys, expectedMetadata)).to.be.deep.equal(clearKeys(keys, body));
    });

    it('unfiltered query index xml', async () => {
        indexName = createIndexName();
        await createInputFormat();

        let createRes = await createIndex(indexName);
        assert.isTrue(createRes.ok);

        let addReq = await addToIndex(indexName, DOC_TO_INDEX_PATH);
        assert.isTrue(addReq.ok);

        let queryInd =  await queryIndex(indexName, '"120"', "", "application/xml")
        assert.isTrue(queryInd.ok);
        var expectedOutput = await toJson(fs.readFileSync((EXPECTED_QUERY_PATH)))
        var body = await toJson(queryInd.body);
        var keys = ['summary']
        expect(clearKeys(keys, expectedOutput['blacklabResponse'])).to.be.deep.equal(clearKeys(keys, body['blacklabResponse']));
    });

    it('filtered query index xml', async () => {
        indexName = createIndexName();
        await createInputFormat();

        let createRes = await createIndex(indexName);
        assert.isTrue(createRes.ok);

        let addReq = await addToIndex(indexName, DOC_TO_INDEX_PATH);
        assert.isTrue(addReq.ok);

        var expectedOutput = await toJson(fs.readFileSync((EXPECTED_QUERY_PATH)));
        var keys = ['summary'];
        var filterTerms = ['"Term" OR "Subscription"', '"Term"'];
        for (let filter of filterTerms) {
            let queryInd = await queryIndex(indexName, '"120"', 'section:(' + filter +')', "application/xml")
            assert.isTrue(queryInd.ok);
            var body = await toJson(queryInd.body);
            expect(clearKeys(keys, expectedOutput['blacklabResponse'])).to.be.deep.equal(clearKeys(keys, body['blacklabResponse']));
        }
    });

    it('no results query index xml', async () => {
        indexName = createIndexName();
        await createInputFormat();

        let createRes = await createIndex(indexName);
        assert.isTrue(createRes.ok);

        let addReq = await addToIndex(indexName, DOC_TO_INDEX_PATH);
        assert.isTrue(addReq.ok);

        var expectedOutput = await toJson(fs.readFileSync((EXPECTED_QUERY_PATH)))
        var keys = ['summary'];
        var filterTerms = ['"Payment"'];
        for (filter in filterTerms) {
            let queryInd = await queryIndex(indexName, '"120"', 'section:(' + filter +')', "application/xml")
            assert.isTrue(queryInd.ok);
            var body = await toJson(queryInd.body);
            var results = clearKeys(keys, body['blacklabResponse']);
            expect(results['hits']).to.be.empty;
        }
    });


});

