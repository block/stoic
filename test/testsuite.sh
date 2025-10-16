#!/bin/bash
#set -x
echo "running test suite"
stoic testsuite
echo "done running test suite - exited with $?"
