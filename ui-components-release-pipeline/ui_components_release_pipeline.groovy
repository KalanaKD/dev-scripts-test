/*
 * Copyright (c) 2018 - 2026, Entgra (Pvt) Ltd. (http://www.entgra.io) All Rights Reserved.
 *
 * Entgra (Pvt) Ltd. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * UI Components release pipeline
 *
 * Releases the packages of the ui-components npm-workspaces monorepo to the Nexus npm-releases repository.
 *   - Only the packages (@entgra/antd, @entgra/mantine, @entgra/mantine-ds) that changed since the last
 *     successful build are released.
 *   - Every changed package must have its version bumped, otherwise nothing is built or published.
 *   - All changed packages are built and verified before the first one is published.
 *   - Re-running a build is safe: a version that already exists in Nexus is skipped (build turns UNSTABLE).
 *   - The result is e-mailed. Runs that changed no package send no e-mail unless they fail.
 *
 * Job setup: Pipeline (script from SCM or pasted), trigger = Poll SCM. This script checks out the
 * ui-components repository itself, so it does not matter which repository the script is stored in.
 */

pipeline {
    agent { label 'docker-lts-jdk11' }

    tools { nodejs 'node version 20.11.1' }

    options {
        skipDefaultCheckout(true)          // this script checks out ui-components itself (see 'Checkout')
        disableConcurrentBuilds()          // never two releases at the same time
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        NEXUS_NPM_TOKEN    = credentials('nexus-npm-token')
        NEXUS_RELEASES_URL = 'https://nexus.entgra.io/repository/npm-releases/'   // same as publishConfig.registry
        REPO_DIR           = 'ui-components'                                      // checkout folder inside the workspace

        // ---- TEST / PROD switches: the only lines to change when going to production ----
        // Source repository.  TEST: fork   PROD: https://github.com/entgra-proprietary/ui-components
        REPO_URL           = 'https://github.com/KalanaKD/ui-components'
        // TEST: 'improvement/jenkins-script'   PROD: 'main'
        REPO_BRANCH        = 'improvement/jenkins-script'
        // Jenkins credential (username + PAT) that can read REPO_URL.
        // TEST: personal PAT credential   PROD: the bot credential (ask the infra team for its ID)
        GIT_CREDENTIALS_ID = 'github-pat-kalana-ui-components-fork'
        // TEST: '@entgra-ui-test/'     PROD: '@entgra-ui/'
        PUBLISH_PREFIX     = '@entgra-ui-test/'
        // TEST: '--tag jenkins-test'   PROD: ''
        PUBLISH_FLAGS      = '--tag jenkins-test'
        // First ever run of this job (no previous successful build exists):
        //   TEST: 'ALL'  -> compare with the root commit, publish everything (baseline test)
        //   PROD: 'NONE' -> compare with HEAD, publish nothing, only record the baseline
        FIRST_RUN_MODE     = 'ALL'
        // TEST: your own address   PROD: 'engineering-group@entgra.io'
        NOTIFY_EMAIL       = 'kalana@entgra.io'
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    env.CURRENT_STAGE = 'Checkout'
                    // Must be the 'checkout' step (not 'sh git clone'), otherwise Poll SCM cannot see this repository.
                    def scmVars = checkout([
                            $class: 'GitSCM',
                            branches: [[name: "*/${env.REPO_BRANCH}"]],
                            userRemoteConfigs: [[url: env.REPO_URL, credentialsId: env.GIT_CREDENTIALS_ID]],
                            extensions: [
                                    [$class: 'RelativeTargetDirectory', relativeTargetDir: env.REPO_DIR],
                                    [$class: 'CleanBeforeCheckout']
                            ]
                    ])
                    env.HEAD_COMMIT = scmVars.GIT_COMMIT
                    env.PREV_SUCCESS_COMMIT = scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: ''
                }
            }
        }

        // 1. Which packages changed since the last SUCCESSFUL build?
        stage('Detect Changed Packages') {
            steps {
                script {
                    env.CURRENT_STAGE = 'Detect Changed Packages'
                    dir(env.REPO_DIR) {
                        def head = env.HEAD_COMMIT
                        def root = sh(script: 'git rev-list --max-parents=0 HEAD | tail -n 1', returnStdout: true).trim()
                        env.BASE_COMMIT = env.PREV_SUCCESS_COMMIT ?: (env.FIRST_RUN_MODE == 'NONE' ? head : root)
                        echo "Comparing ${env.BASE_COMMIT} -> ${head}"

                        def out = sh(script: "git diff --name-only ${env.BASE_COMMIT} HEAD", returnStdout: true).trim()
                        def files = out ? out.split('\n') : []
                        echo "Changed files:\n${out}"

                        def selected = []
                        for (p in ['antd', 'mantine', 'mantine-ds']) {
                            def prefix = "@entgra/${p}/".toString()
                            if (files.any { it.startsWith(prefix) }) {
                                selected << p
                            }
                        }
                        env.CHANGED_PKGS = selected.join(',')
                        env.BUILD_ANY = selected ? 'true' : 'false'
                        echo "Packages to release: ${selected ? env.CHANGED_PKGS : '(none)'}"
                    }
                }
            }
        }

        // 2. Check EVERY changed package before building or publishing ANYTHING.
        //    One missing version bump stops the whole run, so we never publish only some packages.
        stage('Validate Version Bumps') {
            when { environment name: 'BUILD_ANY', value: 'true' }
            steps {
                script {
                    env.CURRENT_STAGE = 'Validate Version Bumps'
                    def errors = []
                    dir(env.REPO_DIR) {
                        for (name in env.CHANGED_PKGS.split(',')) {
                            def v = versions(name)
                            echo "${name}: ${v[0]} -> ${v[1]}"
                            if (v[0] == v[1]) {
                                errors << "${name}: version was not bumped (still ${v[1]})"
                            } else if (!isNewer(v[0], v[1])) {
                                errors << "${name}: version went backwards (${v[0]} -> ${v[1]})"
                            }
                        }
                    }
                    if (errors) {
                        error("Version check failed. Nothing was built or published.\n - " + errors.join('\n - '))
                    }
                }
            }
        }

        stage('Install Dependencies') {
            when { environment name: 'BUILD_ANY', value: 'true' }
            steps {
                script { env.CURRENT_STAGE = 'Install Dependencies' }
                dir(env.REPO_DIR) {
                    // 'npm install' (not 'npm ci') until package-lock.json is back in sync with package.json
                    sh 'npm install --no-audit --no-fund'
                }
            }
        }

        stage('Configure Nexus Auth') {
            when { environment name: 'BUILD_ANY', value: 'true' }
            steps {
                script { env.CURRENT_STAGE = 'Configure Nexus Auth' }
                dir(env.REPO_DIR) {
                    // Append only; the repo's own .npmrc (install registry) stays intact.
                    // publishConfig.registry in each package.json decides the publish target.
                    sh '''
                        echo "" >> .npmrc
                        echo "//nexus.entgra.io/repository/npm-releases/:_authToken=${NEXUS_NPM_TOKEN}" >> .npmrc
                    '''
                }
            }
        }

        // 3. Build ALL changed packages first. If any build fails, nothing has been published yet.
        stage('Build Changed Packages') {
            when { environment name: 'BUILD_ANY', value: 'true' }
            steps {
                script {
                    env.CURRENT_STAGE = 'Build Changed Packages'
                    dir(env.REPO_DIR) {
                        for (name in env.CHANGED_PKGS.split(',')) {
                            echo "Building ${name}"
                            sh "npm run build:${name}"
                        }
                    }
                }
            }
        }

        // 4. Rename to the publish name (after every build, builds need the original names) and do a dry run
        //    of ALL packages. Packaging problems show up here, before the first real publish.
        stage('Verify Packages (dry run)') {
            when { environment name: 'BUILD_ANY', value: 'true' }
            steps {
                script {
                    env.CURRENT_STAGE = 'Verify Packages (dry run)'
                    dir(env.REPO_DIR) {
                        for (name in env.CHANGED_PKGS.split(',')) {
                            withEnv(["PKG_NAME=${name}", "PKG_PATH=@entgra/${name}"]) {
                                sh 'npm pkg set name=${PUBLISH_PREFIX}${PKG_NAME} --workspace=${PKG_PATH}'
                                sh 'npm publish --dry-run --workspace=${PKG_PATH} ${PUBLISH_FLAGS}'
                            }
                        }
                    }
                }
            }
        }

        // 5. Publish. A version that is already in Nexus (e.g. published by an earlier run that failed half way)
        //    is skipped and the build is marked UNSTABLE, so a re-run (Build Now) can finish the remaining packages.
        stage('Publish Changed Packages') {
            when { environment name: 'BUILD_ANY', value: 'true' }
            steps {
                script {
                    env.CURRENT_STAGE = 'Publish Changed Packages'
                    dir(env.REPO_DIR) {
                        for (name in env.CHANGED_PKGS.split(',')) {
                            withEnv(["PKG_NAME=${name}", "PKG_PATH=@entgra/${name}"]) {
                                def alreadyPublished = sh(returnStatus: true, script: '''
                                    VER=$(node -p "require('./${PKG_PATH}/package.json').version")
                                    [ -n "$(npm view "${PUBLISH_PREFIX}${PKG_NAME}@${VER}" version --registry="${NEXUS_RELEASES_URL}" 2>/dev/null)" ]
                                ''') == 0
                                if (alreadyPublished) {
                                    echo "SKIP ${name}: ${env.PUBLISH_PREFIX}${name} is already in Nexus at this version"
                                    env.SKIPPED_PKGS = appendTo(env.SKIPPED_PKGS, name)
                                    currentBuild.result = 'UNSTABLE'
                                } else {
                                    echo "Publishing ${name} as ${env.PUBLISH_PREFIX}${name}"
                                    sh 'npm publish --workspace=${PKG_PATH} ${PUBLISH_FLAGS}'
                                    env.PUBLISHED_PKGS = appendTo(env.PUBLISHED_PKGS, name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            // Removes the workspace, including the token written to .npmrc.
            deleteDir()
        }
        success  { script { notifyBuild('SUCCESS') } }
        unstable { script { notifyBuild('UNSTABLE') } }
        failure  { script { notifyBuild('FAILURE') } }
    }
}

// Returns [versionAtBaseCommit, currentVersion] for @entgra/<name> ('none' if it did not exist at the base commit).
// Must be called inside dir(REPO_DIR).
def versions(String name) {
    def out = ''
    withEnv(["PKG_PATH=@entgra/${name}"]) {
        out = sh(script: '''
            PREV=$(git show ${BASE_COMMIT}:${PKG_PATH}/package.json 2>/dev/null | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).version" 2>/dev/null || echo none)
            CURR=$(node -p "require('./${PKG_PATH}/package.json').version")
            echo "${PREV} ${CURR}"
        ''', returnStdout: true).trim()
    }
    return out.split(' ')
}

// true when newV is a higher version than oldV ('none' means the package is new, so always true)
def isNewer(String oldV, String newV) {
    if (oldV == 'none') {
        return true
    }
    def ok = false
    withEnv(["OLD_V=${oldV}", "NEW_V=${newV}"]) {
        ok = sh(returnStatus: true, script: '[ "$(printf "%s\\n%s\\n" "$OLD_V" "$NEW_V" | sort -V | tail -n 1)" = "$NEW_V" ]') == 0
    }
    return ok
}

def appendTo(String list, String item) {
    return list ? "${list},${item}" : item
}

// E-mails the result. A run that changed no package is silent unless it failed.
def notifyBuild(String result) {
    if (result != 'FAILURE' && env.BUILD_ANY != 'true') {
        return
    }
    def changed = env.CHANGED_PKGS ?: '(none)'
    def published = env.PUBLISHED_PKGS ?: '(none)'
    def skipped = env.SKIPPED_PKGS ?: '(none)'
    def remaining = []
    if (env.CHANGED_PKGS) {
        for (p in env.CHANGED_PKGS.split(',')) {
            if (!(published.split(',').contains(p)) && !(skipped.split(',').contains(p))) {
                remaining << p
            }
        }
    }
    def hint = ''
    if (result == 'FAILURE' && env.PUBLISHED_PKGS) {
        hint = 'PARTIAL RELEASE: some packages are already in Nexus. Fix the cause and run Build Now on the same commit; ' +
                'published packages are skipped and the remaining ones are published.'
    } else if (result == 'FAILURE') {
        hint = 'Nothing was published.'
    } else if (result == 'UNSTABLE') {
        hint = 'Some packages were skipped because that version already exists in Nexus.'
    }
    emailext(
            to: env.NOTIFY_EMAIL,
            subject: "UI Components Release ${result} (build #${env.BUILD_NUMBER})",
            body: """UI Components release pipeline: ${result}

Failed stage / last stage: ${env.CURRENT_STAGE ?: 'unknown'}
Changed packages : ${changed}
Published        : ${published}
Skipped (exists) : ${skipped}
Not published    : ${remaining ? remaining.join(',') : '(none)'}
Compared commits : ${env.BASE_COMMIT ?: '?'} -> ${env.HEAD_COMMIT ?: '?'}

${hint}

Build: ${env.BUILD_URL}
""",
            attachLog: true
    )
}