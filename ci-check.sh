#!/usr/bin/env bash
# Ждёт последний прогон CI по ветке и печатает аннотации (логи джоба из песочницы недоступны).
cd /home/user/Kelemnfno || exit 1
RID=$(gh run list --branch arena/01a0afbd-kelemnfno --limit 1 --json databaseId --jq '.[0].databaseId')
echo "run=$RID"
for i in $(seq 1 40); do
  s=$(gh api repos/KamiSakyy/Kelemnfno/actions/runs/$RID --jq '.status + " " + (.conclusion // "-")')
  case "$s" in completed*) echo "[$i] $s"; break;; esac
  sleep 30
done
JID=$(gh api repos/KamiSakyy/Kelemnfno/actions/runs/$RID/jobs --jq '.jobs[0].id')
gh api "repos/KamiSakyy/Kelemnfno/check-runs/$JID/annotations" \
  --jq '.[] | select(.annotation_level!="warning") | .annotation_level + " >> " + (.message | gsub("[\r\n]+";" | "))' \
  | grep -v "Gradle internals\|at org.gradle" | head -50
