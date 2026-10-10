// cloud-code Chat's bridge to the native half (src/CloudChatPlugin.java). Every call is a Promise
// except stream(), which calls onEvent for each {type: head|data|end|error}. The OpenRouter token is
// never returned by any of these: status() answers {set, masked, source, ...} only.
var exec = require('cordova/exec');

function call(action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, reject, 'CloudChat', action, args || []);
    });
}

module.exports = {
    tokenSet: function (token) { return call('tokenSet', [token]); },
    tokenClear: function () { return call('tokenClear'); },
    tokenStatus: function () { return call('tokenStatus'); },
    tokenTest: function () { return call('tokenTest'); },
    catalogue: function (sections, refresh) { return call('catalogue', [sections || [], !!refresh]); },
    get: function (url, headers, auth) { return call('get', [url, headers || {}, auth || '']); },
    post: function (url, body, headers) { return call('post', [url, body || '{}', headers || {}]); },
    stream: function (request, onEvent) {
        exec(onEvent, function (e) { onEvent({ type: 'error', message: String(e) }); }, 'CloudChat', 'stream', [request]);
    },
    cancel: function (id) { return call('cancel', [id]); },
    pick: function (kind, multiple) { return call('pick', [kind, multiple !== false]); },
    recordStart: function () { return call('recordStart'); },
    recordStop: function () { return call('recordStop'); }
};
