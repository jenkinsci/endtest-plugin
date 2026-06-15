# Endtest Jenkins Plugin

The Endtest Jenkins Plugin runs Endtest automated tests from Jenkins Pipeline jobs and waits for their results.

It supports individual executions and requests that start multiple executions, including label-based Endtest API requests.

## Features

* Run Endtest automated tests from Jenkins Pipeline
* Store Endtest API credentials securely in Jenkins
* Support credentials included in an existing Endtest API request
* Wait for Endtest executions to complete
* Support single and multiple execution hashes
* Resume polling after a Jenkins controller restart
* Return execution results to the Pipeline
* Fail the Jenkins build when Endtest reports failures or errors
* Support `json` and `json-light` result formats

## Requirements

* Jenkins 2.479.3 or newer
* Pipeline plugin
* An Endtest account with API credentials

## Credentials

In Jenkins, open:

`Manage Jenkins` → `Credentials` → `Add Credentials`

Select **Endtest API credentials** and enter:

* App ID
* App Code
* A credential ID, such as `endtest-api`

Storing credentials in Jenkins is recommended.

For compatibility with existing Endtest API requests, `appId` and `appCode` may also be included in `apiRequest`.

Values inside `apiRequest` take precedence. Jenkins credentials provide any values missing from the request.

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
                        apiRequest: 'https://app.endtest.io/api.php?...',
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
                        echo "Execution ${index + 1}: ${execution.testSuiteName}"
                        echo "Configuration: ${execution.configuration}"
                        echo "Hash: ${execution.hash}"
                        echo "Results: ${execution.resultsUrl}"
                    }
                }
            }
        }
    }
}
```

## Parameters

| Parameter | Required | Default | Description |
| --- | --- | --- | --- |
| `apiRequest` | Yes | None | Endtest API request used to start the execution |
| `credentialsId` | Conditional | None | Jenkins Endtest credential ID |
| `timeoutMinutes` | No | `30` | Maximum number of minutes to wait |
| `pollIntervalSeconds` | No | `30` | Delay between result checks |
| `failBuild` | No | `true` | Fail the build when Endtest reports failures or errors |
| `resultsFormat` | No | `json-light` | Result format: `json` or `json-light` |

`credentialsId` is optional when both `appId` and `appCode` are included in `apiRequest`.

## Multiple executions

An API request can start one execution or multiple executions, for example when using an Endtest label.

For multiple executions, the plugin:

* Stores all returned execution hashes
* Polls the hashes together
* Returns individual execution results
* Calculates aggregate totals
* Resumes polling the same hashes after Jenkins restarts

## Returned result

The step returns a map containing:

* `executionCount`
* `hashes`
* `executions`
* `resultsUrls`
* `testCases`
* `passed`
* `failed`
* `errors`
* `multipleExecutions`

Each item in `executions` contains:

* `testSuiteName`
* `configuration`
* `testCases`
* `passed`
* `failed`
* `errors`
* `startTime`
* `endTime`
* `hash`
* `resultsUrl`
* `detailedLogs`
* `screenshotsAndVideo`
* `testCaseManagement`

## Development

Run all checks:

```shell
mvn verify
```

Start a local Jenkins development instance:

```shell
mvn hpi:run
```

The generated plugin package is:

```text
target/endtest.hpi
```

## Issues

Report problems and feature requests through the repository's GitHub Issues page.

## License

Licensed under the [MIT License](LICENSE).
