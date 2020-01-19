# 快速开始

> 我们以一个通过 socket 接收数据，将数据分割为多个字段，并输出处理结果的应用为例，快速展示Waterdrop的使用方法。

### Step 1: 准备 Spark 运行环境

> 如果你熟悉Spark或者已准备好Spark运行环境，可忽略此步骤，Spark不需要做任何特殊配置。

请先[下载 Spark](http://spark.apache.org/downloads.html), Spark版本请选择 >= 2.x.x。下载解压后，不需要做任何配置即可提交Spark deploy-mode = local模式的任务。
如果你期望任务运行在Standalone集群或者Yarn、Mesos集群上，请参考Spark官网的[Spark部署文档](http://spark.apache.org/docs/latest/cluster-overview.html)。

### Step 2: 下载 Waterdrop

进入[Waterdrop安装包下载页面](https://github.com/InterestingLab/waterdrop/releases/tag/v2.0.0-pre)，下载最新版`Waterdrop-<version>.zip`

或者直接下载指定版本（以2.0.0-pre为例）：

```
wget https://github.com/InterestingLab/waterdrop/releases/download/v2.0.0-pre/waterdrop-dist-2.0.0-pre-2.11.8-release.zip -O waterdrop-2.0.0.zip
```

下载后，解压：

```
unzip waterdrop-<version>.zip
ln -s waterdrop-<version> waterdrop
```

### Step 3: 配置 Waterdrop

编辑 `config/waterdrop-env.sh`, 指定必须环境配置如SPARK_HOME(Step 1 中Spark下载并解压后的目录)

新建 `config/application.conf`, 它决定了Waterdrop启动后，数据输入，处理，输出的方式和逻辑。

```
env {
  # Waterdrop defined streaming batch duration in seconds
  spark.streaming.batchDuration = 5

  spark.app.name = "Waterdrop"
  spark.ui.port = 13000
}

source {
  socketStream {}
}

transform {
  split {
    fields = ["msg", "name"]
    delimiter = ","
  }
}

sink {
  console {}
}

```

### Step 4: 启动netcat server用于发送数据

```
nc -lk 9999
```


### Step 5: 启动Waterdrop

```
cd waterdrop
./bin/start-waterdrop-spark.sh --master local[4] --deploy-mode client --config ./config/application.conf

```

### Step 6: 在nc端输入

```
Hello World, Waterdrop
```
Waterdrop日志打印出:

```
+----------------------+-----------+---------+
|raw_message           |msg        |name     |
+----------------------+-----------+---------+
|Hello World, Waterdrop|Hello World|Waterdrop|
+----------------------+-----------+---------+
```


### 总结

Waterdrop 简单易用，还有更丰富的数据处理功能等待被发现。本文展示的数据处理案例，无需任何代码、编译、打包，比官方的[Quick Example](https://spark.apache.org/docs/latest/streaming-programming-guide.html#a-quick-example)更简单。


---

如果想了解更多的Waterdrop配置示例可参见：


[配置示例2 : Batch 离线批处理](https://github.com/InterestingLab/waterdrop/blob/master/config/spark.batch.conf.template)

以上配置为默认【离线批处理配置模版】，可直接运行，命令如下：

```
cd waterdrop
./bin/start-waterdrop-spark.sh --master 'local[2]' --deploy-mode client --config ./config/spark.batch.conf.template
```
