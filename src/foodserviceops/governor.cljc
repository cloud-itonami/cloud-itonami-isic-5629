(ns foodserviceops.governor
  "FoodServiceGovernor -- the independent compliance layer that earns
  the FoodServiceAdvisor the right to commit. The advisor has no notion
  of whether a facility is actually registered and verified (i.e. its
  service contract is on file), whether its own proposed `:effect`
  secretly claims a direct actuation instead of a mere proposal, or
  whether it has silently drifted into a permanently out-of-scope
  decision area, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (service-record logging, service-operation scheduling, supply-order
  coordination, food-safety-concern flagging). It NEVER performs or
  authorizes:
    - finalizing a food-safety-clearance decision
    - overriding an allergen-exclusion requirement
    - direct kitchen-equipment actuation/control
    - food-safety-authority enforcement (health-department clearance,
      inspection sign-off, permit/license suspension, compliance
      enforcement)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Facility unverified       -- the target facility (service
                                    contract) record must exist AND be
                                    independently confirmed
                                    `:registered?`/`:verified?` in the
                                    store before ANY proposal for it
                                    may commit or even escalate. Never
                                    trusts a proposal's own claim about
                                    the facility -- re-derived from the
                                    facility's own store record, the
                                    same 'ground truth, not self-report'
                                    discipline every sibling actor's
                                    governor uses.
    2. Effect not :propose       -- every proposal's `:effect` MUST be
                                    `:propose`. Any other effect value
                                    is, by construction, a claim to
                                    directly actuate/commit outside
                                    governance -- HARD block, not
                                    merely low-confidence.
    3. Scope exclusion           -- ANY proposal (regardless of op)
                                    whose op, rationale, summary,
                                    citations or draft value touches
                                    food-safety-clearance-finalization/
                                    allergen-exclusion-override/
                                    kitchen-equipment-actuation/
                                    food-safety-authority territory is
                                    a HARD, PERMANENT block -- this
                                    actor's charter excludes that
                                    territory structurally, not as a
                                    rollout milestone. Evaluated
                                    UNCONDITIONALLY on every proposal.
                                    An op outside the closed four-op
                                    allowlist is the SAME failure mode
                                    (an advisor proposing something it
                                    was never authorized to propose)
                                    and is folded into this same check.

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-food-safety-concern` -- ALWAYS escalates to a
      human, regardless of confidence, regardless of how clean the
      proposal otherwise is. `foodserviceops.phase` independently
      agrees: `:flag-food-safety-concern` is never a member of any
      phase's `:auto` set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      procurement proposal always needs a human sign-off, even when the
      governor and phase would otherwise allow auto-commit."
  (:require [clojure.string :as str]
            [foodserviceops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example institutional-canteen procurement threshold (USD-equivalent
  units, domain-illustrative -- not a universal cross-domain constant).
  A `:coordinate-supply-order` proposal citing an `:estimated-cost`
  above this value ALWAYS escalates to human sign-off, regardless of
  confidence or rollout phase."
  500.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-service-record :schedule-service-operation
    :coordinate-supply-order :flag-food-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-food-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- finalizing a food-safety-
  clearance decision, overriding an allergen-exclusion requirement,
  directly actuating kitchen equipment, or food-safety-authority
  enforcement. Scanned across the proposal's op/summary/rationale/
  cites/value, never trusting the advisor's own framing of its intent."
  ["food safety clearance" "food-safety-clearance" "finalize clearance"
   "finalized clearance" "clearance decision" "food safety approval"
   "食品安全認可の確定" "食品安全認可を確定" "衛生証明書の発行" "衛生証明の発行"
   "override allergen" "overrides allergen" "overrode allergen"
   "allergen exclusion override" "allergen-exclusion override"
   "waive allergen" "bypass allergen" "ignore allergen" "allergen override"
   "アレルゲン除外" "アレルゲン上書き" "アレルゲン無視"
   "kitchen equipment override" "direct equipment control"
   "actuate equipment" "equipment override" "shut down equipment"
   "power off equipment" "control the oven" "control the fryer"
   "health department" "health inspector" "inspection clearance"
   "regulatory clearance" "compliance sign-off" "license suspension"
   "license-suspension" "permit revocation" "permit-revocation"
   "保健所" "衛生当局" "営業許可取消" "違反"])

;; ----------------------------- checks -----------------------------

(defn- facility-unverified-violations
  "The target facility (service contract) must exist AND be
  independently `:registered?`/`:verified?` in the store -- never trust
  the proposal's own `:facility-id` claim without a store lookup."
  [{:keys [facility-id]} st]
  (let [f (store/facility st facility-id)]
    (when-not (and f (:registered? f) (:verified? f))
      [{:rule :facility-unverified
        :detail (str facility-id " は未登録または未検証の施設(サービス契約) -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches food-safety-clearance-finalization/
  allergen-exclusion-override/kitchen-equipment-actuation/food-safety-
  authority territory, regardless of confidence or how clean every
  other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "食品安全認可の確定/アレルゲン除外要件の上書き/厨房設備の直接操作/食品安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost`
  above `supply-cost-threshold` -- always needs human sign-off (SOFT
  escalate, not a hard block: the order itself is in scope, only its
  size requires a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a FoodServiceAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [facility-id (or (:facility-id proposal) (:facility-id request))
        hard (into []
                   (concat (facility-unverified-violations {:facility-id facility-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :facility-id (:facility-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
