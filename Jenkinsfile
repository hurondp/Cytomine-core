node {
    stage 'Retrieve sources'
    checkout([
        $class: 'GitSCM',  branches: [[name: 'refs/heads/'+env.BRANCH_NAME]],
        extensions: [[$class: 'CloneOption', noTags: false, shallow: false, depth: 0, reference: '']],
        userRemoteConfigs: scm.userRemoteConfigs,
    ])

    stage 'Clean'
    sh 'rm -rf ./ci'
    sh 'mkdir -p ./ci'

    stage 'Compute version name'
    sh 'scriptsCI/ciBuildVersion.sh ${BRANCH_NAME}'

    stage 'Download and cache dependencies'
    sh 'scriptsCI/ciDownloadDependencies.sh'

    lock('cytomine-instance-test') {
        stage 'Run external tools (db, amqp,...)'
        catchError {
            sh 'docker-compose -f scriptsCI/docker-compose.yml down -v'
        }
        sh 'docker-compose -f scriptsCI/docker-compose.yml up -d'

        stage 'Build and test'
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            sh 'scriptsCI/ciTest.sh'
        }
        stage 'Publish test'
        step([$class: 'JUnitResultArchiver', testResults: '**/ci/test-reports/TESTS-TestSuites.xml'])

        catchError {
            sh 'docker-compose -f scriptsCI/docker-compose.yml down -v'
        }
    }
    stage 'Build war'
    sh 'scriptsCI/ciBuildWar.sh'

    withFolderProperties{
        // if PRIVATE is define in jenkins, the war and the docker image are send to the private cytomine repository.
        // otherwise, public repo for war and public dockerhub repo for docker image
        echo("Private: ${env.PRIVATE}")

        if (env.PRIVATE && env.PRIVATE.equals("true")) {
            stage 'Publish war'
            sh 'scriptsCI/ciPublishWarPrivate.sh'

            stage 'Build docker image'
            withCredentials(
                [
                    usernamePassword(credentialsId: 'DOCKERHUB_CREDENTIAL', usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_TOKEN')
                ]
                ) {
                    docker.withRegistry('https://index.docker.io/v1/', 'DOCKERHUB_CREDENTIAL') {
                        sh 'scriptsCI/ciBuildDockerImage.sh'
                    }
                }
        } else {
            stage 'Publish war'
            sh 'scriptsCI/ciPublishWar.sh'

            stage 'Build docker image'
            withCredentials(
                [
                    usernamePassword(credentialsId: 'DOCKERHUB_CREDENTIAL', usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_TOKEN')
                ]
                ) {
                    docker.withRegistry('https://index.docker.io/v1/', 'DOCKERHUB_CREDENTIAL') {
                        sh 'scriptsCI/ciBuildDockerImage.sh'
                    }
                }
        }
    }
}
