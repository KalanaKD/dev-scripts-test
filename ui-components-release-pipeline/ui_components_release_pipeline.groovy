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
 *   - "Since the last successful build" = the newest earlier build that finished SUCCESS or UNSTABLE.
 *     Each build records its commit in its description ("Commit: <sha>"), which is where the next build reads it.
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
                    // Read the baseline BEFORE this build writes its own description.
                    env.PREV_SUCCESS_COMMIT = lastReleasedCommit()
                    currentBuild.description = "Commit: ${env.HEAD_COMMIT}"
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
                    def bad = []
                    def info = []
                    dir(env.REPO_DIR) {
                        for (name in env.CHANGED_PKGS.split(',')) {
                            def v = versions(name)
                            echo "${name}: ${v[0]} -> ${v[1]}"
                            info << "${name}|${v[0]}|${v[1]}".toString()
                            if (v[0] == v[1]) {
                                errors << "${name}: version was not bumped (still ${v[1]})"
                                bad << name
                            } else if (!isNewer(v[0], v[1])) {
                                errors << "${name}: version went backwards (${v[0]} -> ${v[1]})"
                                bad << name
                            }
                        }
                    }
                    env.VERSION_INFO = info.join(';')
                    if (errors) {
                        env.BAD_PKGS = bad.join(',')
                        env.FAIL_REASON = errors.join('\n')
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

// Commit of the newest earlier build that finished SUCCESS or UNSTABLE ('' if there is none).
// (The git plugin's GIT_PREVIOUS_SUCCESSFUL_COMMIT ignores UNSTABLE builds, so we keep our own record.)
def lastReleasedCommit() {
    def b = currentBuild.previousBuild
    while (b != null) {
        if ((b.result == 'SUCCESS' || b.result == 'UNSTABLE') && b.description?.startsWith('Commit: ')) {
            return b.description.substring(8).trim()
        }
        b = b.previousBuild
    }
    return ''
}

// true when item is one of the comma separated names in csv
def inList(String csv, String item) {
    return csv ? csv.split(',').any { it == item } : false
}

def esc(String s) {
    return (s ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

def shortSha(String s) {
    return s ? (s.length() > 8 ? s.substring(0, 8) : s) : '?'
}

// E-mails the result as a structured HTML mail. A run that changed no package is silent unless it failed.
// A problem while building the mail must never change the result of the release, hence the try/catch.
def notifyBuild(String result) {
    if (result != 'FAILURE' && env.BUILD_ANY != 'true') {
        return
    }
    try {
        def colors = [SUCCESS: '#2e7d32', UNSTABLE: '#b26a00', FAILURE: '#c62828']
        def accent = colors[result] ?: '#444444'
        def published = env.PUBLISHED_PKGS ?: ''
        def skipped = env.SKIPPED_PKGS ?: ''
        def bad = env.BAD_PKGS ?: ''

        def verMap = [:]
        for (item in (env.VERSION_INFO ?: '').split(';')) {
            def f = item.split('\\|')
            if (f.length == 3) {
                verMap[f[0]] = esc(f[1]) + ' &rarr; ' + esc(f[2])
            }
        }

        def cell = 'padding:6px 12px;border:1px solid #dddddd;'
        def rows = ''
        if (env.CHANGED_PKGS) {
            for (p in env.CHANGED_PKGS.split(',')) {
                def status = 'Not published'
                def statusColor = '#555555'
                if (inList(published, p)) {
                    status = 'Published'
                    statusColor = '#2e7d32'
                } else if (inList(skipped, p)) {
                    status = 'Skipped (version already in Nexus)'
                    statusColor = '#b26a00'
                } else if (inList(bad, p)) {
                    status = 'Version problem'
                    statusColor = '#c62828'
                }
                rows += """<tr><td style="${cell}"><b>${p}</b></td><td style="${cell}">${verMap[p] ?: '&mdash;'}</td><td style="${cell}color:${statusColor};font-weight:bold;">${status}</td></tr>"""
            }
        }
        def table = rows ? """<table style="border-collapse:collapse;margin:12px 0;"><tr style="background:#f0f0f0;"><th style="${cell}text-align:left;">Package</th><th style="${cell}text-align:left;">Version</th><th style="${cell}text-align:left;">Result</th></tr>${rows}</table>""" : '<p>No package changed.</p>'

        def reason = ''
        if (env.FAIL_REASON) {
            reason = """<p style="margin:12px 0 4px 0;"><b>Why it failed</b></p><p style="margin:0;color:#c62828;">${esc(env.FAIL_REASON).replace('\n', '<br>')}</p>"""
        }

        def action = 'Nothing was published. Check the log, fix the cause and push, or run Build Now.'
        if (result == 'SUCCESS') {
            action = 'All changed packages were published.'
        } else if (result == 'UNSTABLE') {
            action = 'Some packages were skipped because that version already exists in Nexus. If this is unexpected, check that the version was bumped for the new code.'
        } else if (env.BAD_PKGS) {
            action = 'Bump the version in package.json of each package marked "Version problem", then commit and push. The pipeline runs again automatically. Nothing was published.'
        } else if (env.PUBLISHED_PKGS) {
            action = 'PARTIAL RELEASE: some packages are already in Nexus. Fix the cause and run Build Now on the same commit; published packages are skipped and the remaining ones are published.'
        }

        def logTail = ''
        if (result == 'FAILURE' && !env.BAD_PKGS) {
            logTail = '<p style="margin:16px 0 4px 0;"><b>Last log lines</b></p>' +
                    '<pre style="background:#f5f5f5;padding:8px;font-size:12px;white-space:pre-wrap;">' +
                    '${BUILD_LOG, maxLines=30, escapeHtml=true}' + '</pre>'
        }

        def body = """<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#222222;">
<h2 style="margin:0 0 8px 0;color:${accent};">UI Components release: ${result}</h2>
<p style="margin:0 0 4px 0;">Job: <b>${esc(env.JOB_NAME)}</b> &nbsp;|&nbsp; Build: <a href="${env.BUILD_URL}">#${env.BUILD_NUMBER}</a></p>
<p style="margin:0 0 4px 0;">Stage: <b>${esc(env.CURRENT_STAGE ?: 'unknown')}</b></p>
<p style="margin:0;">Commits compared: ${shortSha(env.BASE_COMMIT)} &rarr; ${shortSha(env.HEAD_COMMIT)}</p>
${reason}
${table}
<p style="margin:12px 0;"><b>What to do:</b> ${action}</p>
<p style="margin:0;"><a href="${env.BUILD_URL}console">Open the console log</a> (the full log is attached)</p>
${logTail}
</div>"""

        emailext(
                to: env.NOTIFY_EMAIL,
                subject: "UI Components release ${result} (${env.JOB_NAME} #${env.BUILD_NUMBER})",
                mimeType: 'text/html',
                body: body,
                attachLog: true
        )
    } catch (Exception e) {
        echo "E-mail could not be sent: ${e}"
    }
}