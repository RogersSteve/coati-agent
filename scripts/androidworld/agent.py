"""One APK task per official AndroidWorld episode; no local scoring."""
import time
from android_world.agents import base_agent
from android_world.env import json_action


class EnvironmentFailure(RuntimeError):
    """A device/connection/protocol failure, distinct from agent task failure."""


class CoatiAgent(base_agent.EnvironmentInteractingAgent):
    def __init__(self, env, bridge, spec, perturbation, clock=time.monotonic, sleep=time.sleep):
        super().__init__(env, name="Coati APK", transition_pause=None)
        self.bridge, self.spec, self.perturbation = bridge, spec, perturbation
        self.clock, self.sleep = clock, sleep
        self.result = None
        self.agent_failure = self.env_failure = None
        self.started = False
        self.wall_s = 0.0

    def reset(self, go_home=False):
        if self.started:
            raise RuntimeError("An episode agent cannot be reset or replayed")
        super().reset(go_home)

    def step(self, goal):
        if self.started:
            raise RuntimeError("RUN_TASK must not be sent twice")
        if goal != self.spec["goal"] or self._max_steps != self.spec["official_max_steps"]:
            raise ValueError("Official goal/budget mismatch before task dispatch")
        self.started = True
        start = self.clock()
        try:
            self.bridge.safe_clear()
            self.perturbation.before_task()
            self.bridge.run(goal, self.spec["run_id"], self._max_steps)
            self.perturbation.task_started()
            deadline = start + self.spec["wall_limit_s"]
            while self.clock() < deadline:
                self.sleep(min(3, deadline - self.clock()))
                remaining = deadline - self.clock()
                if remaining <= 0:
                    break
                result = self.bridge.result(self.spec["run_id"], timeout=min(30, remaining))
                if result is not None:
                    if result.get("max_steps") != self._max_steps or result.get("goal") != goal:
                        raise EnvironmentFailure("Matching run_id contains wrong goal/budget")
                    if result.get("status") not in ("completed", "failed", "cancelled"):
                        continue
                    expected = self.spec["condition"]
                    if result.get("verify") is not expected["verify"] or result.get("tree_withheld") is not expected["withhold_tree"]:
                        raise EnvironmentFailure("TaskResult configuration differs from the protocol")
                    self.result = result
                    if result["status"] != "completed":
                        self.agent_failure = result.get("reason") or result["status"]
                    break
            if self.result is None:
                self.agent_failure = "wall_timeout_or_no_matching_result"
        except (KeyboardInterrupt, SystemExit):
            self.env_failure = "interrupted"
            raise
        except Exception as error:
            self.env_failure = type(error).__name__ + ": " + str(error)
        finally:
            self.wall_s = self.clock() - start
            try:
                self.bridge.stop()
            except Exception as error:
                self.env_failure = "stop_failed:" + type(error).__name__
            try:
                self.perturbation.finish()
            except Exception as error:
                self.env_failure = "perturbation_cleanup_failed:" + type(error).__name__
        if self.env_failure is None and self.result is not None:
            answer = self.result.get("answer")
            if answer is not None and not isinstance(answer, str):
                self.env_failure = "invalid_answer_type"
            elif answer is not None and answer.strip():
                self.env.execute_action(json_action.JSONAction(action_type=json_action.ANSWER, text=answer))
                if self.env.interaction_cache != answer:
                    raise EnvironmentFailure("Official ANSWER did not set interaction_cache")
        # Wrapper termination is separate from Coati declared_done and official success.
        return base_agent.AgentInteractionResult(done=True, data={"coati": self.result,
            "agent_failure": self.agent_failure, "env_failure": self.env_failure})
