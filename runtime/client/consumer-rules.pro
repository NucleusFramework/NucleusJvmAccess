# The façades on ART: the client reaches NJA's server by name, the server the façades' modules through ServiceLoader
-keep class dev.nucleusframework.nja.server.NjaServer { public static byte[] handle(byte[], dev.nucleusframework.nja.server.Link); }
-keep interface dev.nucleusframework.nja.server.Link { *; }
-keep class * extends dev.nucleusframework.nja.server.NjaModule { <init>(); }
