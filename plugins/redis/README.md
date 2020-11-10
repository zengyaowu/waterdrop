```
打包使用shade 插件， 将common-pool 重命名， 使用redis-1.0-SNAPSHOT.jar 这个jar 包即可。
redis {
 host = ""    //redis host, default localhost
 port = ""    //redis port, default 6379
 auth= ""     //redis password, nullable
 dbNum = ""   //redis db, nullable
 timeout = "" // connect timeout, default 2000
 table = ""   // key prefix: "$table:"
 key.column = ""  // key
 model = ""   // binary or hash, default hash
}
```
