#!/system/bin/sh
MODDIR=${0%/*}
mkdir -p "$MODDIR"
touch "$MODDIR/config.properties"
chmod 0644 "$MODDIR/config.properties"
