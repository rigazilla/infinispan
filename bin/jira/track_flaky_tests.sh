#!/bin/bash
set -e
# A script to track flaky tests in Jira
# Requires xmlstarlet and jq to be installed
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "${SCRIPT_DIR}/common.sh"

requiredEnv TOKEN PROJECT_KEY TYPE JENKINS_JOB_URL FLAKY_TEST_GLOB TARGET_BRANCH

shopt -s nullglob globstar
TESTS=(${FLAKY_TEST_GLOB})
for TEST in "${TESTS[@]}"; do
  TEST_CLASS_NAMES=$(xmlstarlet sel -t --value-of '/testsuite/testcase/@classname'  ${TEST})
  declare -i i
  for TEST_CLASS in $TEST_CLASS_NAMES; do
    # just get the first one for now
    i=1
    TEST_NAME=$(xmlstarlet sel --template --value-of '/testsuite/testcase['$i']/@name' ${TEST})
    # Removing (Flaky Test) text
    TEST_NAME=${TEST_NAME% (Flaky Test)}
    # Some tests have arguments with backslash, ie testReplace\[NO_TX, P_TX\]. Removing
    TEST_NAME=${TEST_NAME%%\[*}
    # Some tests have it without backslash or have fail counter, ie testContainsAll[1]. Removing
    TEST_NAME=${TEST_NAME%%[*}
    # Some tests end with \(. Removing
    TEST_NAME_NO_PARAMS=${TEST_NAME%%\(*}
    STACK_TRACE=$(xmlstarlet sel --template --value-of '/testsuite/testcase/failure['$i']' ${TEST})

    # Create Issue for Test Class+TestName
    SUMMARY="Flaky test: ${TEST_CLASS}#${TEST_NAME_NO_PARAMS}"
    echo ${SUMMARY}
    #JQL="project = ${PROJECT_KEY} AND summary ~ '${SUMMARY}'"
    # Search issues for existing Jira issue
    # ISSUES="$(curl ${API_URL}/search -G --data-urlencode "jql=${JQL}")"
    # TOTAL_ISSUES=$(echo "${ISSUES}" | jq -r .total)

    # Search issues for existing github issue
      ISSUES="$(gh search issues "${SUMMARY} in:title" --json number)"
      TOTAL_ISSUES=$(echo "${ISSUES}" | jq length)
    if [ ${TOTAL_ISSUES} -gt 1 ]; then
      gh issue create --title "Multiple issues for same flaky test: ${TESTCLASS}" \
      --body "Flaky test ${SUMMARY}\m Please delete all but one of issues:\n $ISSUES"
      exit
    fi

    if [ ${TOTAL_ISSUES} == 0 ]; then
      echo "Existing Jira not found, creating a new one"
#      cat << EOF | tee create-jira.json
#    {
#      "fields": {
#        "project": {
#          "id": "${PROJECT_ID}"
#        },
#        "summary": "${SUMMARY}",
#        "issuetype": {
#          "id": "${ISSUE_TYPE_ID}"
#        },
#        "labels": [
#          "flaky-test"
#        ]
#      }
#    }
#EOF
    gh issue create --title "Flaky Test: ${SUMMARY}" --body "Target Branch: ${TARGET_BRANCH}\n${STACK_TRACE}" --label "Flaky Test"
      # We retry on error here as for some reason the Jira server occasionally responds with 400 errors
      # export ISSUE_KEY=$(curl --retry 5 --retry-all-errors --data @create-jira.json $API_URL/issue | jq -r .key)
    else
      export ISSUE_KEY=$(echo "${ISSUES}" | jq  '.[0].number')
      # Re-open the issue if it was previously resolved
      # TRANSITION="New" ${SCRIPT_DIR}/transition.sh
      if [ "$(gh issue view ${ISSUE_KEY} --json state | jq .state)" == "OPEN" ]; then
        gh issue reopen ${ISSUE_KEY}
      fi
      gh issue comment --body "Target Branch: ${TARGET_BRANCH}\n${STACK_TRACE}"
    fi
  done
done
