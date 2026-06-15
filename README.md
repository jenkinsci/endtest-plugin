# Endtest Jenkins Plugin

The Endtest Jenkins Plugin runs Endtest automated tests from Jenkins Pipeline jobs and waits for their results.

It supports individual executions and requests that start multiple executions, including label-based Endtest API requests.

## Features

* Run Endtest tests from Jenkins Pipeline
* Store Endtest API credentials securely in Jenkins
* Support credentials already included in an Endtest API request
* Wait for test execution completion
* Support single and multiple execution hashes
* Resume polling after a Jenkins restart
* Return execution results to the Pipeline
* Fail the Jenkins build when Endtest reports failures or errors
* Support `json` and `json-light` result formats

## Credentials

In Jenkins, open:

`Manage Jenkins → Credentials → Add Credentials`

Select `Endtest API credentials` and enter:

* App ID
* App Code
* A credential ID such as `endtest-api`

Storing credentials in Jenkins is recommended.

For compatibility with existing Endtest API requests, `appId` and `appCode` may also be present in `apiRequest`.

Values inside `apiRequest` take precedence. Jenkins credentials provide values that are missing from the request.

## Pipeline example

```groovy
pipeline {
    agent any

    stages {
        stage('Run Endtest') {
            steps {
                script {
                    def result = endtestRun(
                        credentialsId: 'endtest-api',
                        apiRequest: 'PASTE_THE_ENDTEST_API_REQUEST_HERE',
                        timeoutMinutes: 30,
                        pollIntervalSeconds: 30,
                        failBuild: true,
                        resultsFormat: 'json-light'
                    )

                    echo "Executions: ${result.executionCount}"
                    echo "Test cases: ${result.testCases}"
                    echo "Passed: ${result.passed}"
                    echo "Failed: ${result.failed}"
                    echo "Errors: ${result.errors}"

                    result.executions.eachWithIndex { execution, index ->
                        echo "Execution ${index + 1}"
                        echo "Suite: ${execution.testSuiteName}"
                        echo "Configuration: ${execution.configuration}"
                        echo "Hash: ${execution.hash}"
                        echo "Results: ${execution.resultsUrl}"
                    }
                }
            }
        }
    }
}
````

## Parameters

| Parameter             | Required    | Default      | Description                                            |
| --------------------- | ----------- | ------------ | ------------------------------------------------------ |
| `apiRequest`          | Yes         | None         | Endtest API request used to start the execution        |
| `credentialsId`       | Conditional | None         | Jenkins Endtest credential ID                          |
| `timeoutMinutes`      | No          | `30`         | Maximum time to wait                                   |
| `pollIntervalSeconds` | No          | `30`         | Delay between result checks                            |
| `failBuild`           | No          | `true`       | Fail the build when Endtest reports failures or errors |
| `resultsFormat`       | No          | `json-light` | Either `json` or `json-light`                          |

`credentialsId` is optional when both `appId` and `appCode` are included in `apiRequest`.

## Returned result

The step returns aggregate values including:

* `executionCount`
* `hashes`
* `executions`
* `resultsUrls`
* `testCases`
* `passed`
* `failed`
* `errors`
* `multipleExecutions`

Each execution contains its suite name, configuration, counters, timestamps, hash, and result URL.

## Development

Run all checks:

```shell
mvn verify
```

Start the local Jenkins development instance:

```shell
mvn hpi:run
```

The generated plugin package is:

```text
target/endtest.hpi
```

## License

MIT License
