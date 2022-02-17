const SERVER_URL = process.env.APP_URL || "http://localhost:8080/blacklab-server";
const BLACKLAB_USER = process.env.BLACKLAB_USER || "user";
const BLACKLAB_PASSWORD = process.env.BLACKLAB_USER || "";
const DEFAULT_WINDOW_SIZE = parseInt(process.env.BLACKLAB_DEFAULT_WINDOW_SIZE) || 50;
const SHOULD_HAVE_CONTEXT = 'RESPONSE_SHOULD_HAVE_CONTEXT' in process.env
    ? JSON.parse(process.env.RESPONSE_SHOULD_HAVE_CONTEXT)
    : true;
const INDEX_TEST_DATA_ROOT = process.env.INDEX_TEST_DATA_ROOT || "data";

module.exports = {
    SERVER_URL,
    DEFAULT_WINDOW_SIZE,
    BLACKLAB_USER,
    BLACKLAB_PASSWORD,
    SHOULD_HAVE_CONTEXT,
    INDEX_TEST_DATA_ROOT
};
