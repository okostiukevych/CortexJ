module io.cortexj.streaming {
    requires transitive io.cortexj.api;
    // audit 2026-10-08 (#148 follow-up): internal is not public API -
    // qualified to the in-repo runtime that needs PushTokenStream on the
    // module path; everyone else uses io.cortexj.stream.TokenStream
    exports io.cortexj.stream.internal to io.cortexj.runtime.remote;
}
