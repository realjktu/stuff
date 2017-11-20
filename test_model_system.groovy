/*

 2052  git clone ssh://oiurchenko@gerrit.mcp.mirantis.net:29418/salt-models/mcp-virtual-lab
 2054  git checkout -b test01
 2055  git pull ssh://oiurchenko@gerrit.mcp.mirantis.net:29418/salt-models/mcp-virtual-lab refs/changes/22/12422/2
 2057  git remote remove origin
 2058  git remote add origin git@github.com:realjktu/reclass-test.git
 2059  git push --set-upstream origin test01

*/

git = new com.mirantis.mk.Git()


def work_dir = 'test_dir01'
def model_repo = 'https://gerrit.mcp.mirantis.net/salt-models/mcp-virtual-lab'
def merge_branch = 'test01'
def refs = 'refs/changes/22/12422/2'
def temp_repo = 'https://github.com/realjktu/stuff'
def credentialsId = 'oiurchenko_github_key'

node('python') {
	stage ('Checkout') {	
			git_test = sh (
				script: 'pwd; ls -la',
				returnStdout: true
				).trim()
			println(git_test)
			sh "rm -rf ${work_dir}"
	        sh "git clone ${model_repo} ${work_dir}"
            dir (work_dir){		
		        sh "git checkout -b ${merge_branch}"
		        sh "git pull ${model_repo} ${refs}"
		        sh "git remote remove origin"
		        sh "git remote add temp_repo ${temp_repo}"
		        sh "git remote -v"
		        sh "git status"
		        git.ushGitChanges(work_dir, merge_branch, 'temp_repo', credentialsId)
            }
            sh "rm -rf ${work_dir}"       
	}
}
