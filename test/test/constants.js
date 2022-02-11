const SERVER_URL = process.env.APP_URL || "http://localhost:8080/blacklab-server";
const BLACKLAB_USER = process.env.BLACKLAB_USER || "user";
const BLACKLAB_PASSWORD = process.env.BLACKLAB_USER || "";
const DEFAULT_WINDOW_SIZE = 50;

module.exports = {
    SERVER_URL,
    DEFAULT_WINDOW_SIZE,
    BLACKLAB_USER,
    BLACKLAB_PASSWORD
};
