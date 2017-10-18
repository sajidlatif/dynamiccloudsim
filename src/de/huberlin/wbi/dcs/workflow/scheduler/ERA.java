package de.huberlin.wbi.dcs.workflow.scheduler;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;

import de.huberlin.wbi.dcs.examples.Parameters;
import de.huberlin.wbi.dcs.workflow.Task;

public class ERA extends AbstractWorkflowScheduler {

	protected final double alpha = 0.01;
	protected final double rho = 0.1;

	public static boolean printEstimatesVsRuntimes = true;
	public static boolean logarithmize = false;

	protected DecimalFormat df;
	protected int runId;
	Random numGen;

	protected Map<String, Queue<Task>> readyTasksPerBot;
	protected Map<String, Set<Task>> runningTasksPerBot;
	protected Map<Vm, Map<String, Set<Task>>> runningTasksPerBotPerVm;
	protected Map<Vm, Map<String, WienerProcessModel>> runtimePerBotPerVm;
	
	protected Set<Task> todo;

	public class Runtime {
		public final double timestamp;
		public final double runtime;

		public Runtime(double timestamp, double runtime) {
			this.timestamp = timestamp;
			this.runtime = runtime;
		}

		@Override
		public String toString() {
			return df.format(timestamp) + "," + df.format(runtime);
		}
	}

	public class WienerProcessModel {
		protected String botName;
		protected int vmId;

		protected Deque<Runtime> measurements;
		protected Queue<Double> differences;
		protected double sumOfDifferences;
		protected Deque<Runtime> estimates;

		public WienerProcessModel(int vmId) {
			this("", vmId);
		}

		public WienerProcessModel(String botName, int vmId) {
			this.botName = botName;
			this.vmId = vmId;
			measurements = new LinkedList<>();
			differences = new LinkedList<>();
			estimates = new LinkedList<>();
		}

		public void printEstimatesVsRuntimes() {
			String filename = "run" + runId + "_vm" + vmId + "_" + botName + ".csv";
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
				writer.write("time;estimate;measurement\n");
				for (Runtime measurement : measurements) {
					double m = logarithmize ? Math.pow(Math.E, measurement.runtime) : measurement.runtime;
					writer.write(df.format(measurement.timestamp / 60) + ";;" + df.format(m / 60) + "\n");
				}
				for (Runtime estimate : estimates) {
					double e = logarithmize ? Math.pow(Math.E, estimate.runtime) : estimate.runtime;
					writer.write(df.format(estimate.timestamp / 60) + ";" + df.format(e / 60) + ";\n");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public void addRuntime(double timestamp, double runtime) {
			if (runtime < 0) {
				System.out.println("help");
			}
			Runtime measurement = new Runtime(timestamp, logarithmize ? Math.log(runtime) : runtime);
			if (!measurements.isEmpty()) {
				Runtime lastMeasurement = measurements.getLast();
				double difference = (measurement.runtime - lastMeasurement.runtime) / Math.sqrt((measurement.timestamp - lastMeasurement.timestamp));
				sumOfDifferences += difference;
				differences.add(difference);
			}
			measurements.add(measurement);
		}

		public double getEstimate(double timestamp) {
			if (differences.size() < 2) {
				return 0d;
			}

			Runtime lastMeasurement = measurements.getLast();

			double variance = 0d;
			double avgDifference = sumOfDifferences / differences.size();
			for (double difference : differences) {
				variance += Math.pow(difference - avgDifference, 2d);
			}
			variance /= differences.size() - 1;

			variance *= timestamp - lastMeasurement.timestamp;

			double estimate = lastMeasurement.runtime;
			if (variance > 0d) {
				NormalDistribution nd = new NormalDistribution(lastMeasurement.runtime, Math.sqrt(variance));
				estimate = nd.inverseCumulativeProbability(alpha);
			}

			if (printEstimatesVsRuntimes) {
				Runtime runtime = new Runtime(timestamp, estimate);
				estimates.add(runtime);
			}
			return logarithmize ? Math.pow(Math.E, estimate) : Math.max(estimate, 0d);
		}
	}

	public ERA(String name, int taskSlotsPerVm, int runId) throws Exception {
		super(name, taskSlotsPerVm);
		readyTasksPerBot = new HashMap<>();
		runningTasksPerBot = new HashMap<>();
		runningTasksPerBotPerVm = new HashMap<>();
		runtimePerBotPerVm = new HashMap<>();
		numGen = Parameters.numGen;
		// stageintimePerMBPerVm = new HashMap<>();
		this.runId = runId;
		Locale loc = new Locale("en");
		df = (DecimalFormat) NumberFormat.getNumberInstance(loc);
		df.applyPattern("###.####");
		df.setMaximumIntegerDigits(7);
		keepReplicatesRunning = true;
	}

	// this function is called once at the beginning of workflow execution
	@Override
	public void reschedule(Collection<Task> tasks, Collection<Vm> vms) {
		for (Vm vm : vms) {
			if (!runtimePerBotPerVm.containsKey(vm)) {
				Map<String, WienerProcessModel> runtimePerBot = new HashMap<>();
				runtimePerBotPerVm.put(vm, runtimePerBot);

				Map<String, Set<Task>> runningTasksPerBot_ = new HashMap<>();
				runningTasksPerBotPerVm.put(vm, runningTasksPerBot_);
			}
		}

		Set<String> bots = new HashSet<>();
		for (Task task : tasks) {
			bots.add(task.getName());
		}
		for (String bot : bots) {
			for (Vm vm : vms) {
				runtimePerBotPerVm.get(vm).put(bot, new WienerProcessModel(bot, vm.getId()));
			}
		}
		
		todo = new HashSet<>(tasks);
	}

	@Override
	public Task getNextTask(Vm vm) {
		boolean replicate = false;

		Map<String, Queue<Task>> b_ready = readyTasksPerBot;
		Map<String, Set<Task>> b_run = runningTasksPerBot;
		Map<String, Set<Task>> b_j = runningTasksPerBotPerVm.get(vm);

		if (b_ready.isEmpty() && b_run.equals(b_j)) {
			return null;
		}

		Map<String, Queue<Task>> b_select = b_ready;
		double r = numGen.nextDouble();
		if (b_ready.isEmpty() || r < rho) {
			b_select = new HashMap<>();
			for (Entry<String, Set<Task>> e : b_run.entrySet()) {
				Queue<Task> tasks = new LinkedList<>(e.getValue());
				if (b_j.containsKey(e.getKey()))
					tasks.removeAll(b_j.get(e.getKey()));
				if (!tasks.isEmpty())
					b_select.put(e.getKey(), tasks);
			}
			replicate = true;
		}

		Set<Vm> m = runningTasksPerBotPerVm.keySet();
		Queue<Task> b_min = null;
		double s_min = Double.MAX_VALUE;
		for (Entry<String, Queue<Task>> b_i : b_select.entrySet()) {
			double e_j = runtimePerBotPerVm.get(vm).get(b_i.getKey()).getEstimate(CloudSim.clock());
			double e_min = e_j;
			double e_max = e_j;

			for (Vm k : m) {
				if (k.equals(vm))
					continue;
				double e_k = runtimePerBotPerVm.get(k).get(b_i.getKey()).getEstimate(CloudSim.clock());
				if (e_k < e_min) {
					e_min = e_k;
				}
				if (e_k > e_max) {
					e_max = e_k;
				}
			}

			double s = (e_max == 0) ? 0d : (e_j - e_min) / e_max;
			if (s < s_min) {
				s_min = s;
				b_min = b_i.getValue();
			}
		}

		if (b_min != null && !b_min.isEmpty()) {
			Task task = b_min.remove();

			if (replicate) {
				task = new Task(task);
				task.setSpeculativeCopy(true);
			} else if (readyTasksPerBot.get(task.getName()).isEmpty()) {
				readyTasksPerBot.remove(task.getName());
			}

			if (!runningTasksPerBot.containsKey(task.getName())) {
				Set<Task> s = new HashSet<>();
				runningTasksPerBot.put(task.getName(), s);
			}
			runningTasksPerBot.get(task.getName()).add(task);

			if (!runningTasksPerBotPerVm.get(vm).containsKey(task.getName())) {
				Set<Task> s = new HashSet<>();
				runningTasksPerBotPerVm.get(vm).put(task.getName(), s);
			}
			runningTasksPerBotPerVm.get(vm).get(task.getName()).add(task);

			return task;
		}

		return null;
	}

	@Override
	public void taskReady(Task task) {
		if (!readyTasksPerBot.containsKey(task.getName())) {
			Queue<Task> q = new LinkedList<>();
			readyTasksPerBot.put(task.getName(), q);
		}
		readyTasksPerBot.get(task.getName()).add(task);
	}

	@Override
	public boolean tasksRemaining() {
		return !todo.isEmpty();
	}

	@Override
	public void taskSucceeded(Task task, Vm vm) {
		double runtime = task.getFinishTime() - task.getExecStartTime();
		runtimePerBotPerVm.get(vm).get(task.getName()).addRuntime(task.getFinishTime(), runtime);
		todo.remove(task);

		taskFinished(task, vm);
	}

	@Override
	public void taskFailed(Task task, Vm vm) {
		taskFinished(task, vm);
	}

	private void taskFinished(Task task, Vm vm) {
		if (runningTasksPerBot.containsKey(task.getName())) {
			runningTasksPerBot.get(task.getName()).remove(task);
			if (runningTasksPerBot.get(task.getName()).isEmpty())
				runningTasksPerBot.remove(task.getName());
		}

		runningTasksPerBotPerVm.get(vm).get(task.getName()).remove(task);
		if (runningTasksPerBotPerVm.get(vm).get(task.getName()).isEmpty())
			runningTasksPerBotPerVm.get(vm).remove(task.getName());
	}

	@Override
	public void terminate() {
		if (printEstimatesVsRuntimes) {
			for (Map<String, WienerProcessModel> m : runtimePerBotPerVm.values()) {
				for (WienerProcessModel w : m.values()) {
					w.printEstimatesVsRuntimes();
				}
			}
		}
	}

}