#!/bin/bash
usage="Usage: dir_diff.sh path [days]"

if [ ! "$1" ]
then
  echo $usage
  exit 1
fi

now=$(date +%s)
hadoop fs -ls $1 | grep -v "^d" | while read f; do
  dir_date=`echo $f | awk '{print $6}'`
  difference=$(( ( $now - $(date -d "$dir_date" +%s) ) / (24 * 60 * 60 ) ))
  if [ $difference -gt $2 ]; then
    echo $f | awk '{print $8}';
  fi
done

