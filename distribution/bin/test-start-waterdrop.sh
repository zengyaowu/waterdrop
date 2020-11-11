#!/bin/bash


CMD_ARGUMENTS=$@


PARAMS=""
while (( "$#" )); do
  case "$1" in
    -m|--master)
      MASTER=$2
      shift 2
      ;;

    -e|--deploy-mode)
      DEPLOY_MODE=$2
      shift 2
      ;;
    -e|--queue)
      QUEUE=$2
	  shift 2
	  ;;

    -a|--appname)
	  APPNAME=$2
	  shift 2
	  ;;


    -c|--config)
      CONFIG_FILE=$2
      shift 2
      ;;
    -d|--tdate)
      TDATE=$2
      shift 2
      ;;
    -i|--variable)
      variable=$2
      java_property_value="-D${variable}"
      variables_substitution="${java_property_value} ${variables_substitution}"
      shift 2
      ;;

    --) # end argument parsing
      shift
      break
      ;;

    # -*|--*=) # unsupported flags
    #  echo "Error: Unsupported flag $1" >&2
    #  exit 1
    #  ;;

    *) # preserve positional arguments
      PARAM="$PARAMS $1"
      shift
      ;;

  esac
done
# set positional arguments in their proper place
eval set -- "$PARAMS"
# 获取配置名. 配置应该加上时间戳，否则是会被覆盖！！！！！！！！！！！！！！！！！切记
tempconfig=${CONFIG_FILE}

BIN_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

APP_DIR=$(dirname ${BIN_DIR})
CONF_DIR=${APP_DIR}/config
LIB_DIR=${APP_DIR}/lib
PLUGINS_DIR_CONF="-Dplugin.dir=${APP_DIR}/plugins"

DEFAULT_CONFIG=${CONF_DIR}/application.conf
CONFIG_FILE=${CONFIG_FILE:-$DEFAULT_CONFIG}

DEFAULT_MASTER=local[2]
MASTER=${MASTER:-$DEFAULT_MASTER}

DEFAULT_QUEUE="root.default"
echo "BIN_DIR $BIN_DIR"
echo "APP_DIR $APP_DIR"

QUEUE=${QUEUE:-$DEFAULT_QUEUE}
DEFAULT_DEPLOY_MODE=client
DEPLOY_MODE=${DEPLOY_MODE:-$DEFAULT_DEPLOY_MODE}

ts_config=${ts_config:-$CONFIG_FILE}

CMD_ARGUMENTS="--master yarn --deploy-mode client --config ${ts_config}"


FilesDepOpts=""
if [ "$DEPLOY_MODE" == "cluster" ]; then

    ## add config file
    FilesDepOpts="--files ${ts_config}"

    ## add plugin files
    FilesDepOpts="${FilesDepOpts},${APP_DIR}/plugins.tar.gz"

    echo ""

elif [ "$DEPLOY_MODE" == "client" ]; then

    echo ""
fi

assemblyJarName=$(find ${APP_DIR} -name waterdrop*.jar)
echo "assemblyJarName ${assemblyJarName}"

string_trim() {
    echo $1 | awk '{$1=$1;print}'
}

variables_substitution=$(string_trim "${variables_substitution}")


function get_spark_conf {
    spark_conf=$(java ${variables_substitution} -cp "${APP_DIR}/*"  io.github.interestinglab.waterdrop.config.ExposeSparkConf ${CONFIG_FILE})

    if [ "$?" != "0" ]; then
        echo "[ERROR] config file does not exists or cannot be parsed due to invalid format"
        exit -1
    fi
    echo ${spark_conf}
}

sparkconf=$(get_spark_conf)

echo "spark conf is $sparkconf"

function get_plugin_conf {
    plugin_conf=$(java ${variables_substitution} -cp "${APP_DIR}/*" io.github.interestinglab.waterdrop.config.ExposePluginConf ${CONFIG_FILE} ${APP_DIR})

    if [ "$?" != "0" ]; then
        echo "[ERROR] config file does not exists or cannot be parsed due to invalid format"
        exit -1
    fi
    echo ${plugin_conf}

}

plugin_conf=$(get_plugin_conf)
echo "plugin conf is : $plugin_conf"


#解析读取被用到的插件的jar包，其他的就不用加载
JarDepOpts=${plugin_conf}

# Spark Driver Options
driverJavaOpts=""
executorJavaOpts=""
clientModeDriverJavaOpts=""
if [ ! -z "${variables_substitution}" ]; then
  driverJavaOpts="${variables_substitution}"
  executorJavaOpts="${variables_substitution}"
  # in local, client mode, driverJavaOpts can not work, we must use --driver-java-options
  clientModeDriverJavaOpts="${variables_substitution}"
fi


#export hadoop user token if needed

echo "JarDepOpts: ${JarDepOpts}"


exec /data/apps/spark/bin/spark-submit --class io.github.interestinglab.waterdrop.Waterdrop --master ${MASTER} --queue ${QUEUE} \
--deploy-mode ${DEPLOY_MODE} \
--driver-java-options $PLUGINS_DIR_CONF \
--conf spark.app.name=${APPNAME} \
--conf spark.yarn.maxAppAttempts=1 \
--conf spark.yarn.max.executor.failures=1 \
--conf spark.stage.maxConsecutiveAttempts=1 \
--conf spark.task.maxFailures=1 \
--jars $JarDepOpts \
${sparkconf} \
${FilesDepOpts} \
${assemblyJarName} ${CMD_ARGUMENTS}

