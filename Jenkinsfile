pipeline {
    agent any
    options {
        preserveStashes(buildCount: 20)
    }
    stages {
        stage('build') {
            when {
                anyOf {
                    expression { changedFromOriginTarget() }
                    allOf {
                        branch 'ai2-dev'
                        expression { changedFromPreviousCommit() }
                    }
                }
            }
            steps {
                githubNotify context: 'ci/ann', description: 'Blacklab Build started', status: 'PENDING'
                //sh 'docker-compose build --build-arg CONFIG_ROOT=docker-private --build-arg TOMCAT_APP_NAME=ROOT testserver'
                sh 'DOCKER_BUILDKIT=1 CONFIG_ROOT=docker-private TOMCAT_APP_NAME=ROOT docker build -f docker/Dockerfile .'
                //stash name: 'blacklab-build', includes: 'ann-deploy/'
            }
            post {
                failure {
                    githubNotify context: 'ci/ann', description: 'Build failed', status: 'FAILURE'
                }
                unstable {
                    githubNotify context: 'ci/ann', description: 'Build error', status: 'ERROR'
                }
            }
        }
    }
}

/*
        stage('test') {
            when {
                anyOf {
                    expression { changedFromOriginTarget() }
                    allOf {
                        branch 'main'
                        expression { changedFromPreviousCommit() }
                    }
                }
            }
            steps {
                sh './ann/deploy/ann-cd.sh'
            }
            post {
                failure {
                    githubNotify context: 'ci/ann', description: 'Build failed', status: 'FAILURE'
                }
                unstable {
                    githubNotify context: 'ci/ann', description: 'Build error', status: 'ERROR'
                }
            }
        }
   }
}

        stage('integration') {
            when {
                anyOf {
                    expression { annPRChanged() }
                    allOf {
                        branch 'main'
                        expression { annMainChanged() }
                    }
                }
            }
            steps {
                sh './ann/deploy/run-integration.sh'
            }
            post {
                failure {
                    githubNotify context: 'ci/ann', description: 'Build failed', status: 'FAILURE'
                }
                unstable {
                    githubNotify context: 'ci/ann', description: 'Build error', status: 'ERROR'
                }
            }
        }

        stage('signal-success') {
            steps {
                githubNotify context: 'ci/ann', description: 'Build succeeded', status: 'SUCCESS'
            }
        }
        stage('finish') {
            when {
                branch 'main'
                expression { annMainChanged() }
            }
            steps {
                milestone(30)  // Abort all older builds that didn't get here
            }
        }
    }

    post {
        failure {
            script {
                if (env.BRANCH_NAME == 'main')  {
                    slackSend(channel: "#deployments", color: '#FF0000', message: "Ann Build Pipelined Failed!",  blocks: [
                        [
                            "type": "section",
                            "text": [
                                "type": "mrkdwn",
                                "text": "Ann Build Pipeline Failed:\n*<${env.RUN_DISPLAY_URL}|Build ${env.BUILD_NUMBER}>*"
                            ]
                        ],
                        [
                            "type": "section",
                            "text": [
                                "type": "mrkdwn",
                                "text": "*Commit:*\n<https://github.com/lexionai/Ann/commit/${env.GIT_COMMIT}>"
                            ]
                        ]
                    ])
                }
            }
        }
    }
}
 */

def changedFromPreviousCommit() {
    return sh(returnStatus: true, script: "git diff HEAD~1 --name-only > /dev/null") == 0
}

// Github calls target the base branch. Typically this is main.
def changedFromOriginTarget() {
    return sh(returnStatus: true, script: "git diff --name-only origin/${env.CHANGE_TARGET} HEAD > /dev/null") == 0
}