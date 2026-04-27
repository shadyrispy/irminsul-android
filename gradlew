#!/usr/bin/env bash

# Gradle startup script for Unix

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> .*$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"/"$link"`
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" || exit 1
APP_HOME="`pwd -P`"
cd "$SAVED" || exit 1

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m -XX:MaxMetaspaceSize=256m"

# Use the maximum available from these JVM options to run Gradle
# This may cause Gradle to use more memory than historically it has required
# This takes the same approach as the JVM itself: -Xmx is only used if -XX:MaxRamPercentage is not used
# https://docs.oracle.com/en/java/javase/17/docs/specs/managment/overview.html#xargs
# In the same way, the MaxRAMPercentage and MaxRAM are not used if -XX:MaxMetaspaceSize is set
# https://docs.oracle.com/en/java/javase/17/docs/specs/managment/memory-management.html#xargs
# If you prefer to use a specific Java version, set the JAVA_HOME here
# JAVA_HOME=

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVA_CMD="$JAVA_HOME/jre/sh/java"
    else
        JAVA_CMD="$JAVA_HOME/bin/java"
    fi
    if [ !  -x "$JAVA_CMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVA_CMD="java"
    which java >/dev/null 2>&1 || die "ERROR: no Java found

If you already have Java installed, set the JAVA_HOME environment variable in your shell profile.
"
fi

# Increase the maximum file descriptors if we can
if [ "cygwin" = "$(uname -o 2>/dev/null)" ]; then
    MAX_FD=256
    case $(uname -o) in
        Cygwin) MAX_FD=$(cygpath -wn $((echo 8192; cmd /C DIR 2>/dev/null) 2>/dev/null)
    esac
    [ -z "$MAX_FD" ] || ulimit -n $MAX_FD 2>/dev/null
fi

# For Darwin, add options to specify how the application appears in the dock
if $darwin ; then
    GRADLE_OPTS="-Xdock:name=Gradle $GRADLE_OPTS"
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if [ "$cygwin" = "false" -a "$msys" = "false" ] ; then
    case $uname_o in
        Cygwin) JAVA_CMD=`cygpath -w "$JAVA_CMD"`
    esac
fi

# Escape application args
save () {
    for i; do echo "$i" | sed "s/'/'\\\\''/g; s/^.*$/\"&\"/"; done
    echo " "
}

APP_ARGS=$(save "$@")

# Collect all arguments for the java command, following the shell quoting and space handling
# rules. Handles follows: -args with spaces. -args with quotes and spaces.
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"$APP_HOME\"/lib/gradle-launcher-8.5.jar" org.gradle.launcher.Main "$APP_ARGS"

exec "$JAVA_CMD" "$@"
