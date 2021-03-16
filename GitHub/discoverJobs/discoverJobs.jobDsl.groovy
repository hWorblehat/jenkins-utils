#!/usr/bin/env groovy

@Grab(group='org.kohsuke', module='github-api', version='1.123')
import org.kohsuke.github.*
import java.util.regex.Matcher

final class JobDslFactory {

	static final int jobRunsToKeep = 15
	static final int masterJobRunsToKeep = 30
	static final int triggerJobRunsToKeep = 5
	static final int branchCreateJobRunsToKeep = 2
	static final int branchDeleteJobRunsToKeep = 5
	static final int runDeleteJobRunsToKeep = 2

	private final def dslFactory
	private final Closure echo
	private final String httpCredentialsId
	private final String sshCredentialsId

	JobDslFactory(def dslFactory, Closure echo, String httpCredentialsId, String sshCredentialsId) {
		this.dslFactory = dslFactory
		this.echo = echo
		this.httpCredentialsId = httpCredentialsId
		this.sshCredentialsId = sshCredentialsId
	}

	private String jobPath(String jobFolder, String jobName) {
		return jobFolder ? "${jobFolder}/${jobName}" : jobName
	}

	private void setBuildRetention(def delegate, int numToKeep) {
		delegate.buildRetention {
			buildDiscarder {
				logRotator {
					numToKeepStr("${numToKeep}")
					daysToKeepStr("-1")
					artifactNumToKeepStr("${numToKeep}")
					artifactDaysToKeepStr("-1")
				}
			}
		}
	}

	private String multibranchPipelineJob(
		String message,
		String jobFolder,
		RepoSpec repo,
		PipelineSpec pipeline,
		Closure jobBranchSourceStrategy,
		Closure additionalConfig
	) {
		String path = jobPath(jobFolder, pipeline.name)
		echo "${message}: ${path}"
		dslFactory.multibranchPipelineJob(path) {
			displayName(pipeline.displayName)
			branchSources {
				branchSource {
					source {
						github {
							id('github')
							repositoryUrl(repo.httpUrl)
							configuredByUrl(true)
							repoOwner(repo.owner)
							repository(repo.name)
							credentialsId(httpCredentialsId)
							traits {
								gitHubBranchDiscovery {
									strategyId(3) // Include all branches
								}
								gitHubPullRequestDiscovery {
									strategyId(1) // Merge with target branch before building
								}
								gitHubSshCheckout {
									credentialsId(sshCredentialsId)
								}
								notificationContextTrait {
									contextLabel("jenkins/${pipeline.name}")
									typeSuffix(true)
								}
							}
						}
					}
					strategy {
						jobBranchSourceStrategy.delegate = delegate
						jobBranchSourceStrategy()
					}
				}
			}
			factory {
				workflowBranchProjectFactory {
					scriptPath(pipeline.scriptPath)
				}
			}
			orphanedItemStrategy {
				discardOldItems {
					daysToKeep(7)
				}
			}
			additionalConfig.delegate = delegate
			additionalConfig()
		}
		return path
	}

	String repoFolder(String parent, RepoSpec repo) {
		String path = jobPath(parent, repo.name)
		echo "Adding Jenkins folder: ${path}"
		dslFactory.folder(path) {
			description(repo.description)
			primaryView('Jobs & Hooks')

			views {
				listView('Jobs & Hooks') {
					jobs {
						regex(/(?!.*-(?:onBranchCreate|onRunDelete)-trigger).*/)
					}
					columns {
						status()
						weather()
						name()
						lastSuccess()
					}
				}
				listView('Triggers') {
					description('Automatically created jobs whose sole purpose is to trigger (and be triggered by) other jobs.')
					jobs{
						regex(/.*-(?:onBranchCreate|onRunDelete)-trigger/)
					}
					columns {
						status()
						weather()
						name()
						lastSuccess()
					}
				}
			}
		}
		return path
	}

	String mainJob(
		String jobFolder,
		RepoSpec repo,
		JobSpec job,
		Collection<String> onBranchCreate,
		Collection<String> onBranchDelete,
		Collection<String> onRunDelete
	) {
		return multibranchPipelineJob(
			'Adding job', jobFolder, repo, job,
			{
				namedBranchesDifferent {
					defaultProperties {
						setBuildRetention(delegate, jobRunsToKeep)
					}
					namedExceptions {
						named {
							name(repo.defaultBranch)
							props {
								setBuildRetention(delegate, masterJobRunsToKeep)
							}
						}
					}
				}
			},
			{
				properties {
					pipelineTriggerProperty {
						createActionJobsToTrigger(onBranchCreate.join(','))
						deleteActionJobsToTrigger(onBranchDelete.join(','))
						actionJobsToTriggerOnRunDelete(onRunDelete.join(','))
						branchIncludeFilter('*')
						branchExcludeFilter('')
					}
				}
				triggers {
					periodicFolderTrigger {
						interval('1h')
					}
				}
			}
		)
	}

	String multibranchHookJob(String jobFolder, RepoSpec repo, HookSpec hook, int numToKeep) {
		return multibranchPipelineJob(
			'Adding hook job', jobFolder, repo, hook,
			{
				allBranchesSame {
					props {
						setBuildRetention(delegate, numToKeep)
						suppressAutomaticTriggering()
					}
				}
			},
			{}
		)
	}

	List<String> branchCreateJobs(String jobFolder, RepoSpec repo, Collection<HookSpec> hooks) {
		return hooks.collect { hook ->
			return multibranchHookJob(jobFolder, repo, hook, branchCreateJobRunsToKeep)
		}
	}

	List<String> runDeleteJobs(String jobFolder, RepoSpec repo, Collection<HookSpec> hooks) {
		return hooks.collect { hook ->
			return multibranchHookJob(jobFolder, repo, hook, runDeleteJobRunsToKeep)
		}
	}

	String triggerJob(
		String jobFolder,
		String jobName,
		String jobDisplayName,
		Collection<String> downstream
	) {
		String path = jobPath(jobFolder, jobName)
		echo "Adding hook trigger job: ${path}"
		dslFactory.pipelineJob(path) {
			displayName(jobDisplayName)
			logRotator(-1, triggerJobRunsToKeep)
			definition {
				cps {
					sandbox(true)
					script("""\
						void triggerHook(String name, String branch, List params) {
							String jobName = "\${name}/\${branch}"
							try {
								build job: jobName, parameters: params, wait: false
							} catch (Exception e) {
								if(e.message == "No item named \${jobName} found") {
									echo "Looks like \${name} doesn't exist for branch '\${branch}' yet"
								} else {
									throw e
								}
							}
						}

						pipeline {
							agent any
							stages {
								stage('Scan Hooks') {
									steps {
										${downstream.collect{"build job: '/${it}', wait: false"}.join('\n')}
										sleep ${10 * downstream.size()}
									}
								}
								stage('Trigger Hooks') {
									steps {
										script {
											List hookParams = []
											for(String p : [
												'SOURCE_PROJECT_NAME', 'SOURCE_PROJECT_FULL_NAME',
												'SOURCE_BRANCH_NAME', 'TARGET_BRANCH_NAME',
												'SOURCE_RUN_NUMBER', 'SOURCE_RUN_DISPLAY_NAME'
											]) {
												if(params[p]) {
													echo "Setting downstream parameter: \${p}=\${params[p]}"
													hookParams.add(string(name: p, value: params[p]))
												}
											}
											${downstream.collect{"triggerHook('/${it}', params.SOURCE_PROJECT_NAME, hookParams)"}.join('\n')}
										}
									}
								}
							}
						}
					""".stripIndent())
				}
			}
		}
		return path
	}

	String pipelineHookJob(String jobFolder, RepoSpec repo, HookSpec hook, int numToKeep) {
		String path = jobPath(jobFolder, hook.name)
		echo "Adding hook job: ${path}"
		dslFactory.pipelineJob(path) {
			displayName(hook.displayName)
			logRotator(-1, numToKeep)
			definition {
				cpsScm {
					scm {
						git {
							remote {
								url(repo.sshUrl)
								credentials(sshCredentialsId)
							}
							branch(repo.defaultBranch)
						}
					}
					scriptPath(hook.scriptPath)
				}
			}
		}
		return path
	}

	List<String> branchDeleteJobs(String jobFolder, RepoSpec repo, Collection<HookSpec> hooks) {
		return hooks.collect { hook ->
			return pipelineHookJob(jobFolder, repo, hook, branchDeleteJobRunsToKeep)
		}
	}

}

final class NamingConvention {

	static String hookJobDisplayName(String jobDisplayName, String hookDisplayName, String suffix) {
		return suffix == ''
			? "${jobDisplayName} - ${hookDisplayName}"
			: "${jobDisplayName} - ${hookDisplayName} - ${suffix}"
	}

	static String toDisplayName(String name) {
		return name.replaceAll(/(?<=[a-z])(?=[A-Z])/, ' ').capitalize()
	}

}

abstract class PipelineSpec {
	String scriptPath

	abstract String getName()
	abstract String getDisplayName()
}

final enum HookType {
	BRANCH_CREATE,
	BRANCH_DELETE,
	RUN_DELETE,
	;

	String getMatchString() {
		return "on${name().toLowerCase().split(/_/).collect{ it.capitalize() }.join('')}"
	}

	String getDisplayName() {
		return "On ${name().toLowerCase().split(/_/).collect{ it.capitalize() }.join(' ')}"
	}
}

final class HookSpec extends PipelineSpec {

	JobSpec job
	HookType type
	String suffix

	@Override
	String getName() {
		String n = "${job.name}-${type.matchString}"
		return suffix ? "${n}-${suffix}" : n
	}

	@Override
	String getDisplayName() {
		String n = "${job.displayName} - ${type.displayName}"
		return suffix ? "${n} - ${NamingConvention.toDisplayName(suffix)}" : n
	}

}

final class JobSpec extends PipelineSpec {
	final Map<HookType, Map<String, HookSpec>> hooks = [:]

	String name

	@Override
	String getDisplayName() {
		return NamingConvention.toDisplayName(name)
	}

	void addHook(HookType type, String suffix, String scriptPath) {
		hooks.computeIfAbsent(type) {[:]}
			.computeIfAbsent(suffix) {new HookSpec(
				job: this,
				type: type,
				suffix: suffix,
				scriptPath: scriptPath
			)}
	}
}

final class RepoSpec {

	String sshUrl
	String httpUrl
	String owner
	String name
	String defaultBranch
	String description
	final Map<String, JobSpec> jobs = [:]

	JobSpec jobSpec(String id, String name, String scriptPath) {
		return jobs.computeIfAbsent(id, {new JobSpec(name: name, scriptPath: scriptPath)})
	}

	private void addJob(JobDslFactory dslFactory, String jobFolder, JobSpec job) {

		List<String> onRunDeleteTrigger = []
		if(job.hooks.containsKey(HookType.RUN_DELETE)) {
			List<String> onRunDeletePaths = dslFactory
				.runDeleteJobs(jobFolder, this, job.hooks.get(HookType.RUN_DELETE).values())

			onRunDeleteTrigger += dslFactory.triggerJob(
				jobFolder,
				"${job.name}-onRunDelete-trigger",
				NamingConvention.hookJobDisplayName(job.displayName, "On Run Delete", "Trigger"),
				onRunDeletePaths
			)
		}

		List<String> onBranchCreateTrigger = []
		if(job.hooks.containsKey(HookType.BRANCH_CREATE)) {
			List<String> onBranchCreatePaths = dslFactory
				.branchCreateJobs(jobFolder, this, job.hooks.get(HookType.BRANCH_CREATE).values())

			onBranchCreateTrigger += dslFactory.triggerJob(
				jobFolder,
				"${job.name}-onBranchCreate-trigger",
				NamingConvention.hookJobDisplayName(job.displayName, "On Branch Create", "Trigger"),
				onBranchCreatePaths
			)
		}

		List<String> onBranchDeletePaths = []
		if(job.hooks.containsKey(HookType.BRANCH_DELETE)) {
			onBranchDeletePaths = dslFactory
				.branchDeleteJobs(jobFolder, this, job.hooks.get(HookType.BRANCH_DELETE).values())
		}

		dslFactory.mainJob(jobFolder, this, job, onBranchCreateTrigger, onBranchDeletePaths, onRunDeleteTrigger)
	}

	void createJobs(JobDslFactory dslFactory, String jenkinsParentFolder) {
		if(!jobs.isEmpty()) {
			String jobFolder = dslFactory.repoFolder(jenkinsParentFolder, this)

			for(def job : jobs.values()) {
				addJob(dslFactory, jobFolder, job)
			}
		}
	}

}

new IndentPrinter(new PrintWriter(out)).with { p ->
	Closure echo = {
		p.printIndent()
		p.println it
		p.flush()
	}

	JobDslFactory factory = new JobDslFactory(this, echo, httpCredentialsId, sshCredentialsId)

	GitHub github = GitHub.connectUsingOAuth(apiToken)
	GHPerson person
	try {
		person = github.getOrganization(ghOwner)
	} catch (IOException e) {
		person = github.getUser(ghOwner)
	}
	for(def repo : person.listRepositories(100)) {
		echo "Scanning repository: ${repo.fullName}"
		p.incrementIndent()

		RepoSpec repoSpec = new RepoSpec(
			owner: ghOwner,
			name: repo.name,
			sshUrl: repo.sshUrl,
			httpUrl: repo.httpTransportUrl,
			defaultBranch: repo.defaultBranch,
			description: repo.description
		)

		for(def branch : repo.branches.values()) {
			echo "Scanning branch: ${branch.name}"
			p.incrementIndent()

			List<GHContent> jenkinsDirEntries
			try {
				jenkinsDirEntries = repo.getDirectoryContent('jenkins', branch.name)
			} catch (IOException e) {
				// OK - directory not found in branch
				jenkinsDirEntries = []
			}

			for(GHContent ent : jenkinsDirEntries) {
				if(!ent.directory) continue

				Map<String, GHContent> content = ent.listDirectoryContent().collectEntries {
					[it.name, it]
				}

				if(!content.containsKey('Jenkinsfile')) {
					echo "No Jenkinsfile found in folder: ${ent.path}"
					continue
				}

				String jenkinsfile = content.get("Jenkinsfile").path
				echo "Found pipeline: ${jenkinsfile}"
				JobSpec jobSpec = repoSpec.jobSpec(ent.name, ent.name, jenkinsfile)

				for(GHContent c : content.values()) {
					if(c.name == 'Jenkinsfile') continue
					boolean matched = false
					Matcher m = c.name =~ /^(?:(.*)\.)?([^\.]+)\.Jenkinsfile$/
					if(m) {
						for (HookType hookType : HookType.values()) {
							if(m.group(2) == hookType.matchString) {
								matched = true
								echo "Found ${hookType} pipeline: ${c.path}"
								jobSpec.addHook(hookType, m.group(1), c.path)
								break
							}
						}
					}
					if(!matched) {
						echo "Jenkinsfile name does not match required naming pattern: ${c.path}"
					}
				}
			}
			p.decrementIndent()
		}

		repoSpec.createJobs(factory, null)
		p.decrementIndent()
	}

}
