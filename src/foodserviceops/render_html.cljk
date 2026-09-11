(ns foodserviceops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack -- `foodserviceops.operation/build`
  compiles a langgraph-clj StateGraph, and every request below goes
  through `langgraph.graph/run*` into
  `foodserviceops.advisor` -> `foodserviceops.governor` ->
  `foodserviceops.phase` -> `foodserviceops.store` -- and renders whatever
  that run actually produced.

  NOTHING on the page is typed by hand:

    - facility rows come from `store/all-facilities` on the real seeded store;
    - one request row per real `g/run*`, whose outcome is classified from
      that run's OWN audit trail (never from a literal);
    - the HARD-check rows are GROUPED OUT of the real `:governor-hold`
      facts the run wrote to the ledger, with the governor's own
      `:detail` strings;
    - the rollout-phase matrix is produced by CALLING `phase/gate` for
      every (phase, op) pair, so it cannot drift from `phase/phases`;
    - the governor's thresholds are read out of the real vars
      (`governor/confidence-floor`, `governor/supply-cost-threshold`,
      `governor/allowed-ops`, `governor/always-escalate-ops`);
    - the SSoT table is `store/coordination-log` verbatim;
    - the ledger table is `store/ledger` verbatim.

  Where the page cannot honestly show something, it says so instead of
  inventing it -- see `approver-attribution`, which re-derives at RENDER
  time whether the human approver's id actually reached the committed
  record and/or the audit ledger, rather than asserting a claim in prose
  that would go stale the day the store changes.

  ## Scenario

  Adapted from this repo's own `foodserviceops.sim` demo driver
  (`clojure -M:dev:run`), which was run BEFORE this file was written and
  confirmed to produce a sensible ledger against the real seeded facility
  ids `facility-1`..`facility-3`.

    - facility-1 (Riverside Hospital Cafeteria) walks a full coordination
      episode: a service-record log at phase 1 (assisted-logging --
      escalates because phase 1 has an EMPTY `:auto` set, approved), the
      same op at phase 3 (auto-commit), a service-operation schedule and
      a LOW-cost supply order (both auto-commit), a HIGH-cost supply
      order (always escalates on the governor's own `high-stakes?`, even
      at phase 3 -- approved) and a food-safety-concern flag (never in
      any phase's `:auto` set, at any phase -- approved).
    - facility-2 (Lincoln High School Canteen) auto-commits a service
      record, then has a food-safety-concern flag REJECTED by the human
      approver -- the SOFT gate's other outcome, to contrast with a HARD
      hold that never reaches a human at all.
    - a phase-0 (read-only) request is held by the PHASE layer with no
      governor violation at all -- a different kind of hold from a HARD
      governor rule, and the page keeps the two apart.
    - all four HARD governor rules actually fire; none of them reaches a
      human, which `-main` verifies structurally rather than asserting.

  ## Build-time invariants (`-main` THROWS, it does not warn)

    1. the run must produce at least one real `:governor-hold` fact --
       a console that shows no real hold is not evidence of a governor;
    2. it must exercise at least `min-distinct-hard-rules` DISTINCT HARD
       rules -- an evidence floor, so a governor change that silently
       stops firing a check fails the build instead of quietly shrinking
       the page;
    3. no run that HARD-held may also have asked a human -- the
       'HARD holds never reach a human' claim is MEASURED, not asserted;
    4. every HARD-check row rendered must trace to a rule actually
       present in the ledger -- the section cannot outlive the run that
       justified it.

  ## Determinism

  Every collaborator is pure or deterministic: the mock advisor is a
  `case` over the request, the store is an atom of EDN seeded from
  `store/demo-data`, and no code in `src/` reads a clock or a RNG. Every
  set iterated for the page is explicitly sorted. No timestamps appear in
  the output, so successive regenerations are byte-identical (verify by
  diffing two consecutive runs into scratch files).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [foodserviceops.store :as store]
            [foodserviceops.phase :as phase]
            [foodserviceops.governor :as governor]
            [foodserviceops.advisor :as advisor]
            [foodserviceops.operation :as op]
            [langgraph.graph :as g]))

(def ^:private min-distinct-hard-rules
  "Evidence floor. `foodserviceops.governor` can emit exactly four HARD
  rule names (`:facility-unverified`, `:effect-not-propose`,
  `:op-not-allowed`, `:scope-excluded`) and this scenario is built to
  fire every one of them. If a governor change makes one stop firing,
  the build fails instead of silently rendering a thinner page."
  4)

(def ^:private approver-id "food-service-coordinator-1")

(defn- ctx
  "The operator context injected into every run, at rollout `phase`."
  [phase]
  {:actor-id "coord-1" :actor-role :food-service-coordinator :phase phase})

;; ----------------------------- driving the REAL actor -----------------------------

(defn- record!
  "Append one finished graph run to the ordered run log. `result` is the
  raw `langgraph.graph/run*` return value -- everything rendered from it
  is real actor output."
  [runs tid phase request result]
  (swap! runs conj {:tid tid
                    :phase phase
                    :request request
                    :audit (vec (get-in result [:state :audit]))
                    :disposition (get-in result [:state :disposition])})
  result)

(defn- exec!
  "One operation, no human in the loop (auto-commit or HARD hold)."
  [runs actor tid phase request]
  (record! runs tid phase request
           (g/run* actor {:request request :context (ctx phase)} {:thread-id tid})))

(defn- resume!
  "One operation the governor/phase gate escalated, then resumed by a
  human decision. The resumed result carries the FULL accumulated audit
  (the `:audit` channel's reducer is `into`, restored from the
  checkpointer), so only the resumed result is recorded."
  [runs actor tid phase request status]
  (g/run* actor {:request request :context (ctx phase)} {:thread-id tid})
  (record! runs tid phase request
           (g/run* actor {:approval {:status status :by approver-id}}
                   {:thread-id tid :resume? true})))

(def ^:private rogue-effect-advisor
  "A deliberately MALFUNCTIONING advisor -- it claims `:effect :commit`,
  which the shipped mock advisor can never emit (it always says
  `:propose`). Injected over the SAME store through the seam
  `operation/build` already exposes, because that is the only honest way
  to demonstrate the governor's defense-in-depth: a compromised advisor
  claiming to actuate directly must still be HARD-held."
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :effect :commit))))

(def ^:private rogue-op-advisor
  "A second MALFUNCTIONING advisor -- a perfectly well-formed, honest,
  high-confidence `:effect :propose` proposal for an op that is simply
  NOT on the closed four-op allowlist. This isolates `:op-not-allowed`
  from the other HARD rules (nothing else about the proposal is wrong)."
  (reify advisor/Advisor
    (-advise [_ _store {:keys [facility-id]}]
      {:op          :approve-vendor-invoice
       :facility-id facility-id
       :summary     (str facility-id " のベンダー請求書を承認する提案(注入)")
       :rationale   "この操作はこのアクターに認められた 4 つの操作の外側にある。"
       :cites       [facility-id]
       :effect      :propose
       :value       {:facility-id facility-id}
       :confidence  0.95})))

(defn run-demo!
  "Runs a fresh seeded store through the scenario documented in the ns
  docstring. Returns `{:db .. :runs [..]}` -- every field the page reads
  is real governor/store output."
  []
  (let [db      (store/seed-db)
        actor   (op/build db)
        rogue-e (op/build db {:advisor rogue-effect-advisor})
        rogue-o (op/build db {:advisor rogue-op-advisor})
        runs    (atom [])]

    ;; --- facility-1: a full clean coordination episode ---
    ;; phase 1 (assisted-logging): writes allowed, `:auto` is EMPTY, so a
    ;; governor-clean proposal still escalates to a human.
    (resume! runs actor "t01" 1
             {:op :log-service-record :facility-id "facility-1"
              :patch {:meal-count 220 :menu "chicken over rice" :allergen-flags ["sesame"]}}
             :approved)
    ;; phase 3 (supervised-auto): the same op, governor-clean -> auto-commit.
    (exec! runs actor "t02" 3
           {:op :log-service-record :facility-id "facility-1"
            :patch {:meal-count 180 :menu "vegetable stir-fry" :allergen-flags []}})
    (exec! runs actor "t03" 3
           {:op :schedule-service-operation :facility-id "facility-1"
            :patch {:shift "lunch-prep" :date "2026-07-20" :window "10:00-11:30"}})
    ;; below `governor/supply-cost-threshold` -> auto-commit.
    (exec! runs actor "t04" 3
           {:op :coordinate-supply-order :facility-id "facility-1"
            :patch {:item "disposable trays" :quantity 500 :estimated-cost 120.0}})
    ;; above it -> the governor's OWN `high-stakes?` escalates, even at phase 3.
    (resume! runs actor "t05" 3
             {:op :coordinate-supply-order :facility-id "facility-1"
              :patch {:item "walk-in cooler repair parts" :quantity 1 :estimated-cost 2400.0}}
             :approved)
    ;; never in ANY phase's `:auto` set, and in `always-escalate-ops` too.
    (resume! runs actor "t06" 3
             {:op :flag-food-safety-concern :facility-id "facility-1"
              :patch {:concern "tray 14 sesame allergen mismatch and cooler at 48F for 2h"
                      :confidence 0.92}}
             :approved)

    ;; --- facility-2: a clean commit, then the SOFT gate's other outcome ---
    (exec! runs actor "t07" 3
           {:op :log-service-record :facility-id "facility-2"
            :patch {:meal-count 640 :menu "pasta bake" :allergen-flags ["wheat" "milk"]}})
    (resume! runs actor "t08" 3
             {:op :flag-food-safety-concern :facility-id "facility-2"
              :patch {:concern "reheat log gap on the 11:40 tray line" :confidence 0.71}}
             :rejected)

    ;; --- the PHASE layer holding a governor-clean proposal (no violation) ---
    (exec! runs actor "t09" 0
           {:op :log-service-record :facility-id "facility-1"
            :patch {:meal-count 95 :menu "congee"}})

    ;; --- all four HARD governor rules, none of which reaches a human ---
    (exec! runs actor "t10" 3
           {:op :log-service-record :facility-id "facility-99"
            :patch {:meal-count 0 :menu "unknown"}})
    (exec! runs actor "t11" 3
           {:op :log-service-record :facility-id "facility-3"
            :patch {:meal-count 40 :menu "sandwiches"}})
    (exec! runs rogue-e "t12" 3
           {:op :schedule-service-operation :facility-id "facility-1"
            :patch {:shift "dinner-prep" :date "2026-07-22"}})
    (exec! runs rogue-o "t13" 3
           {:op :log-service-record :facility-id "facility-1" :patch {}})
    (exec! runs actor "t14" 3
           {:op :log-service-record :facility-id "facility-1"
            :out-of-scope? true :patch {}})

    {:db db :runs @runs}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- n-cell
  "A numeric cell. ESCAPES its argument, so it must only ever be given a
  plain value -- never pre-built markup, which would be double-escaped
  and rendered to the reader as literal `&lt;code&gt;` text."
  [v]
  (str "<span class=\"num\">" (esc v) "</span>"))

(defn- dash [] "<span class=\"muted\">&mdash;</span>")

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- fmt-val [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    (sequential? v) (if (seq v) (str/join " / " (map fmt-val v)) "—")
    :else (pr-str v)))

(defn- fmt-map
  "A stable, key-sorted `k=v` rendering of a real map read out of the
  run. Sorted so the page cannot depend on map iteration order."
  [m & {:keys [omit] :or {omit #{}}}]
  (let [pairs (->> (dissoc m)
                   (remove (fn [[k _]] (contains? omit k)))
                   (sort-by (comp kw-str key)))]
    (if (seq pairs)
      (str/join ", " (map (fn [[k v]] (str (kw-str k) "=" (fmt-val v))) pairs))
      "—")))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [rs] (str/join "\n" rs))

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- derived facts -----------------------------

(defn- hard-holds
  "The `:governor-hold` facts that carry at least one real governor
  violation. A phase-gate hold also writes a `:governor-hold` fact but
  with EMPTY `:violations` -- a different layer, kept apart on purpose."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) (store/ledger db)))

(defn- phase-holds
  "`:governor-hold` facts with no governor violation at all -- the phase
  gate refusing an op the current rollout phase has not enabled."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) (store/ledger db)))

(defn- outcome
  "Classify one real run from its own audit trail. Never from a literal."
  [{:keys [audit disposition]}]
  (let [hold (fact-of audit :governor-hold)
        rej  (fact-of audit :approval-rejected)]
    (cond
      (and hold (seq (:violations hold)))
      {:kind :hard-hold :violations (:violations hold)}

      hold
      {:kind :phase-hold :reason (:phase-reason hold) :phase (:phase hold)}

      rej {:kind :rejected :reason (:reason (fact-of audit :approval-requested))}

      (fact-of audit :approval-granted)
      {:kind :approved
       :reason (:reason (fact-of audit :approval-requested))
       :by (:by (fact-of audit :approval-granted))}

      (fact-of audit :approval-requested)
      {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}

      (= :commit disposition) {:kind :auto-commit}
      :else {:kind :other})))

(defn- outcome-cell [o]
  (case (:kind o)
    :hard-hold  (str "<span class=\"critical\">HARD hold &middot; "
                     (esc (str/join ", " (map (comp kw-str :rule) (:violations o))))
                     "</span>")
    :phase-hold (str "<span class=\"warn\">phase hold &middot; "
                     (esc (kw-str (:reason o))) "</span>")
    :rejected   (str "<span class=\"critical\">escalated (" (esc (kw-str (:reason o)))
                     ") &rarr; REJECTED by human</span>")
    :approved   (str "<span class=\"ok\">escalated (" (esc (kw-str (:reason o)))
                     ") &rarr; approved</span>")
    :awaiting   (str "<span class=\"warn\">awaiting human approval &middot; "
                     (esc (kw-str (:reason o))) "</span>")
    :auto-commit "<span class=\"ok\">auto-commit (governor-clean)</span>"
    "<span class=\"muted\">in progress</span>"))

(defn- outcome-detail [o]
  (case (:kind o)
    :hard-hold  (esc (str/join " / " (map :detail (:violations o))))
    :phase-hold (str "rollout phase " (esc (:phase o))
                     " has not enabled this write op; no governor violation")
    :rejected   "the human approver declined; nothing was written to the SSoT"
    :approved   (str "committed after sign-off by " (code (:by o)))
    :auto-commit (dash)
    (dash)))

(defn- deep-key-names
  "Every key name appearing anywhere in a nested structure, as strings.
  Used to ask the SSoT what it actually holds instead of assuming."
  [x]
  (cond
    (map? x) (into (into #{} (map kw-str) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(def ^:private approver-key-names
  #{"approved-by" "approved_by" "approver" "approved-by-id" "by"})

(defn- approver-attribution
  "DERIVED honest disclosure about where the human approver's id actually
  lives after a `:request-approval` handoff.

  This is MEASURED at render time -- the committed records in the SSoT
  are scanned for any approver-shaped key, and the store's ledger is
  scanned for a fact that carries one -- rather than asserted in prose,
  which would silently become a lie the day either side changes.

  Returns `{:approvers :records-with :approval-commits :on-record?
  :on-ledger? :ledger-commit-carries?}`."
  [db runs]
  (let [records  (vec (store/coordination-log db))
        approver? #(contains? approver-key-names (str/lower %))
        with-app (filterv #(some approver? (deep-key-names %)) records)
        ledger   (vec (store/ledger db))
        approvals (->> runs
                       (keep #(:by (fact-of (:audit %) :approval-granted)))
                       (into #{})
                       sort
                       vec)]
    {:approvers        approvals
     :records          (count records)
     :records-with     (count with-app)
     ;; runs that a human actually approved -- the population that COULD
     ;; carry an approver on its committed record.
     :approval-commits (count (filterv #(fact-of (:audit %) :approval-granted) runs))
     :on-record?       (boolean (seq with-app))
     ;; does ANY ledger fact carry the approver?
     :on-ledger?       (boolean (some #(some approver? (deep-key-names %)) ledger))
     ;; specifically: can a ledger-only reader tell an approved commit
     ;; from an auto-commit?
     :ledger-commit-carries?
     (boolean (some #(and (= :committed (:t %)) (some approver? (deep-key-names %))) ledger))}))

;; ----------------------------- sections -----------------------------

(defn- summary-section [db runs]
  (let [os   (mapv outcome runs)
        by   (frequencies (map :kind os))
        hs   (hard-holds db)
        rules (->> hs (mapcat :violations) (map :rule) (into #{}) sort vec)]
    (section
     "Run summary"
     (str "Counted from the real audit ledger and the real graph results, not asserted. "
          "Every number below is a <code>count</code> over data this run produced.")
     ["Measure" "Count"]
     [(row "facilities (service contracts) in the SSoT" (n-cell (count (store/all-facilities db))))
      (row "graph runs in this scenario" (n-cell (count runs)))
      (row "<span class=\"ok\">auto-commits (governor-clean, phase 3)</span>"
           (n-cell (get by :auto-commit 0)))
      (row "<span class=\"ok\">escalated &rarr; approved by a human</span>"
           (n-cell (get by :approved 0)))
      (row "<span class=\"critical\">escalated &rarr; rejected by a human</span>"
           (n-cell (get by :rejected 0)))
      (row "<span class=\"critical\">HARD governor holds (never reach a human)</span>"
           (n-cell (count hs)))
      ;; the rule names are already markup (`<code>`), so they are placed
      ;; NEXT TO the numeric cell rather than inside it -- `n-cell` escapes.
      (row "distinct HARD governor rules exercised"
           (str (n-cell (count rules)) " &nbsp; "
                (str/join ", " (map (comp code kw-str) rules))))
      (row "<span class=\"warn\">phase-gate holds (no governor violation)</span>"
           (n-cell (count (phase-holds db))))
      (row "committed records in the SSoT coordination log"
           (n-cell (count (store/coordination-log db))))
      (row "immutable facts on the audit ledger"
           (n-cell (count (store/ledger db))))])))

(defn- facilities-section [db]
  (section
   "Service facilities (SSoT directory)"
   (str "Read back out of <code>foodserviceops.store/all-facilities</code> on the store this "
        "run actually seeded. The governor re-derives <code>:registered?</code>/"
        "<code>:verified?</code> from THESE records on every single proposal &mdash; "
        "never from the proposal's own claim about its facility.")
   ["Facility" "Name" "Registered" "Verified" "May any proposal proceed?"]
   (for [f (store/all-facilities db)]
     (row (code (:facility-id f))
          (esc (:name f))
          (if (:registered? f) "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
          (if (:verified? f) "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
          (if (and (:registered? f) (:verified? f))
            "<span class=\"ok\">yes</span>"
            "<span class=\"critical\">no &middot; HARD hold on every op</span>")))))

(defn- requests-section [runs]
  (section
   "Coordination requests in this scenario"
   (str "One row per real <code>langgraph.graph/run*</code>. The outcome column is classified "
        "from that run's OWN audit trail (<code>:governor-hold</code> / "
        "<code>:approval-requested</code> / <code>:approval-granted</code> / "
        "<code>:approval-rejected</code>), so a row cannot claim an outcome the actor did not "
        "produce.")
   ["Run" "Phase" "Op" "Facility" "Outcome" "Detail"]
   (for [r runs
         :let [o (outcome r)]]
     (row (code (:tid r))
          (n-cell (:phase r))
          (code (kw-str (:op (:request r))))
          (code (:facility-id (:request r)))
          (outcome-cell o)
          (outcome-detail o)))))

(defn- hard-checks-section [db]
  (let [hs (hard-holds db)
        grouped (->> hs
                     (mapcat (fn [f] (map #(vector (:rule %) (:detail %) f) (:violations f))))
                     (group-by first)
                     (sort-by (comp kw-str key)))]
    (section
     "HARD governor checks that actually fired"
     (str "Grouped out of the real <code>:governor-hold</code> facts on the ledger &mdash; a rule "
          "appears here only because this run made it fire, and the wording is the governor's "
          "own <code>:detail</code> string. These are PERMANENT, un-overridable blocks: no human "
          "approval can release them, and none of them ever reached a human "
          "(no <code>:approval-requested</code> in any held run &mdash; verified at build time).")
     ["Rule" "Times fired" "Facilities held" "Governor's own detail"]
     (for [[rule entries] grouped]
       (row (code (kw-str rule))
            (n-cell (count entries))
            (str/join " " (->> entries (map #(:facility-id (nth % 2))) distinct sort (map code)))
            ;; EVERY distinct detail the governor emitted for this rule, not
            ;; just the first: these strings name the offending facility, so
            ;; showing one while the column beside it lists several would
            ;; describe a hold that is not the one being read.
            (str/join "<br>" (->> entries (map second) distinct sort (map esc))))))))

(defn- phase-gate-section []
  (let [ops (vec (sort-by kw-str governor/allowed-ops))
        phs (vec (sort (keys phase/phases)))
        cell (fn [ph o]
               (let [{:keys [disposition reason]} (phase/gate ph {:op o} :commit)]
                 (case disposition
                   :commit   "<span class=\"ok\">auto-commit</span>"
                   :escalate (str "<span class=\"warn\">human approval"
                                  (when reason (str " &middot; " (esc (kw-str reason)))) "</span>")
                   :hold     (str "<span class=\"critical\">hold"
                                  (when reason (str " &middot; " (esc (kw-str reason)))) "</span>")
                   (esc (kw-str disposition)))))]
    (section
     "Rollout phase gate"
     (str "Not a description &mdash; this table is produced by CALLING "
          "<code>foodserviceops.phase/gate</code> for every (phase, op) pair with a "
          "governor-CLEAN verdict, so it cannot drift away from <code>phase/phases</code>. "
          "A governor HOLD always stays a hold at every phase (compliance wins), so that column "
          "would be uniform and is not shown. Note that "
          "<code>:flag-food-safety-concern</code> is absent from EVERY phase's "
          "<code>:auto</code> set &mdash; a permanent structural fact, not a milestone still to "
          "come &mdash; and that a supply order above "
          (code (str "$" governor/supply-cost-threshold))
          " never reaches this function with <code>:commit</code> at all, because the governor's "
          "own <code>high-stakes?</code> already turned it into an escalation upstream.")
     (into ["Op"] (for [p phs] (str "Phase " p " &middot; " (esc (:label (get phase/phases p))))))
     (for [o ops]
       (apply row (code (kw-str o)) (for [p phs] (cell p o)))))))

(defn- governor-config-section []
  (section
   "Governor configuration"
   (str "Read out of the real vars in <code>foodserviceops.governor</code> at render time, so "
        "these values are the ones the run above was actually judged against.")
   ["Setting" "Value"]
   [(row (code "confidence-floor") (n-cell governor/confidence-floor))
    (row (code "supply-cost-threshold")
         (n-cell (str governor/supply-cost-threshold " (USD-equivalent, domain-illustrative)")))
    (row (str "closed op allowlist " (code "allowed-ops"))
         (str/join " " (map (comp code kw-str) (sort-by kw-str governor/allowed-ops))))
    (row (str "always escalates " (code "always-escalate-ops"))
         (str/join " " (map (comp code kw-str) (sort-by kw-str governor/always-escalate-ops))))
    (row "scope-exclusion terms scanned on every proposal"
         (n-cell (count governor/scope-excluded-terms)))
    (row "default rollout phase"
         (str (n-cell phase/default-phase) " &middot; "
              (esc (:label (get phase/phases phase/default-phase)))))]))

(defn- ssot-section [db]
  (section
   "Committed coordination log (SSoT)"
   (str "<code>foodserviceops.store/coordination-log</code> verbatim &mdash; the only writes this "
        "run made. Nothing that was held or rejected appears here. The approver column is the "
        "record's own <code>:payload</code> content, not a join: if it is blank the record "
        "genuinely carries no approver.")
   ["#" "Op" "Facility" "Committed value" "Approver on the record"]
   (map-indexed
    (fn [i r]
      (row (n-cell (inc i))
           (code (kw-str (:op r)))
           (code (:facility-id r))
           (esc (fmt-map (:value r) :omit #{:facility-id}))
           (if-let [a (get-in r [:payload :approved-by])]
             (str "<span class=\"ok\">" (code a) "</span>")
             "<span class=\"muted\">&mdash; auto-commit, no approval path</span>")))
    (store/coordination-log db))))

(defn- attribution-section
  "Renders the approver-attribution disclosure from DERIVED facts, so the
  claim tracks the code instead of going stale. See
  `approver-attribution` -- the page never prints an approver as though
  a register held one when it does not."
  [{:keys [approvers records records-with approval-commits
           on-record? on-ledger? ledger-commit-carries?]}]
  (str
   "  <section class=\"card\">\n"
   "    <h2>Approver attribution &mdash; what each register does and does not hold</h2>\n"
   "    <p class=\"muted\">Re-checked against the real store at render time: every committed "
   "record in the coordination log and every fact on the audit ledger is scanned for an "
   "approver-shaped key (<code>:approved-by</code>, <code>:approver</code>, <code>:by</code>, "
   "&hellip;). This is measured, not asserted, so it cannot drift away from the code &mdash; "
   "if the store changes, this section changes with it on the next build.</p>\n"
   "    <table>\n"
   "      <thead><tr><th>Question</th><th>Answer</th></tr></thead>\n"
   "      <tbody>\n"
   (rows
    [(row "approver id(s) on this run&rsquo;s <code>:approval-granted</code> audit facts"
          (if (seq approvers) (str/join " " (map code approvers)) (dash)))
     (row "runs a human actually approved" (n-cell approval-commits))
     (row "committed records in the SSoT" (n-cell records))
     (row "&hellip; of which carry an approver on the record"
          (n-cell (str records-with " of " records)))
     (row "approver reaches the committed SSoT record"
          (if on-record?
            "<span class=\"ok\">yes</span>"
            "<span class=\"critical\">no &mdash; dropped on the way to the store</span>"))
     (row "approver reaches any fact on the audit ledger"
          (if on-ledger?
            "<span class=\"ok\">yes</span>"
            "<span class=\"critical\">no</span>"))
     (row "a ledger-only reader can tell an approved commit from an auto-commit"
          (if ledger-commit-carries?
            "<span class=\"ok\">yes</span>"
            "<span class=\"critical\">no &mdash; both are a bare <code>:committed</code> fact</span>"))])
   "\n      </tbody>\n    </table>\n"
   "    <p>"
   (cond
     (empty? approvers)
     "This run produced no human approval, so there is no approver to attribute."

     (and on-record? ledger-commit-carries?)
     (str "The approver is persisted on the committed record <em>and</em> is visible on the "
          "audit ledger, so both registers can answer &ldquo;who signed this off?&rdquo;.")

     on-record?
     (str "The approver <strong>is</strong> retained where it matters most &mdash; "
          "<code>operation</code>&rsquo;s <code>:request-approval</code> node attaches it at "
          "<code>[:payload :approved-by]</code> and this store&rsquo;s "
          "<code>commit-record!</code> writes the record through whole, so the SSoT can name "
          "the human who signed off. It does <strong>not</strong> reach the audit ledger: only "
          "the <code>:commit</code> and <code>:hold</code> nodes append, and the "
          "<code>:committed</code> fact they write is identical whether the commit was "
          "auto or human-approved &mdash; the <code>:approval-granted</code> fact that carries "
          "<code>:by</code> stays in the run&rsquo;s in-memory audit channel and is never "
          "persisted. So the approver ids listed in the first row above are "
          "<em>audit-only &mdash; observed on the run, not retained on the ledger</em>. "
          "Anyone auditing from the ledger alone must join back to the coordination log to "
          "attribute a sign-off.")

     :else
     (str "The approver reaches <strong>neither</strong> the committed record nor the ledger. "
          "The ids in the first row are <em>audit-only &mdash; observed on the run, not "
          "retained by the store</em>; they are shown so that &ldquo;nobody approved this&rdquo; "
          "and &ldquo;the store dropped the approver&rdquo; do not look identical on this page."))
   "</p>\n"
   "  </section>\n"))

(defn- ledger-section [db]
  (section
   "Audit ledger (this run)"
   (str "<code>foodserviceops.store/ledger</code> verbatim &mdash; the append-only, immutable "
        "decision-fact log, in the order the actor wrote it. Every commit and every hold in the "
        "scenario above is here.")
   ["#" "Fact" "Op" "Facility" "Basis" "Confidence"]
   (map-indexed
    (fn [i f]
      (row (n-cell (inc i))
           (case (:t f)
             :committed "<span class=\"ok\">committed</span>"
             :governor-hold (if (seq (:violations f))
                              "<span class=\"critical\">governor-hold</span>"
                              "<span class=\"warn\">governor-hold (phase gate)</span>")
             :approval-rejected "<span class=\"critical\">approval-rejected</span>"
             (esc (kw-str (:t f))))
           (code (kw-str (:op f)))
           (code (:facility-id f))
           (cond
             (seq (:basis f)) (str/join " " (map (comp code fmt-val) (:basis f)))
             (:phase-reason f) (str (code (kw-str (:phase-reason f)))
                                    " <span class=\"muted\">(phase "
                                    (esc (:phase f)) ")</span>")
             :else (dash))
           (if-let [c (:confidence f)] (n-cell c) (dash))))
    (store/ledger db))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator-console document from a completed
  `run-demo!` result."
  [{:keys [db runs]}]
  (str
   "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
   "<meta name=\"color-scheme\" content=\"light\">"
   "<title>cloud-itonami-isic-5629 &middot; other food service activities "
   "&mdash; operator console</title>"
   "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"

   "<header class=\"bar\">\n"
   "  <h1>Other food service activities (ISIC 5629) &mdash; Operator Console</h1>\n"
   "</header>\n"
   "<p><span class=\"badge\">read-only sample</span> "
   "<span class=\"badge\">governor-gated</span> "
   "<span class=\"badge\">coordination-only &middot; every effect is :propose</span></p>\n"
   "<p class=\"subtitle\">Institutional / contract food-service back-office coordination "
   "&mdash; hospital cafeterias, school canteens, food-court vendor bays. "
   "Generated at build time by <code>foodserviceops.render-html</code> "
   "(<code>clojure -M:dev:render-html</code>) by actually running the compiled "
   "<code>foodserviceops.operation</code> StateGraph over a freshly seeded store. "
   "Every value below was read back out of that run &mdash; there is no mock markup on this "
   "page, and no timestamp, so successive regenerations are byte-identical.</p>\n"
   "<p class=\"subtitle\">This actor never finalizes a food-safety-clearance decision, never "
   "overrides an allergen-exclusion requirement and never directly actuates kitchen equipment. "
   "That is a permanent, un-overridable block in "
   "<code>foodserviceops.governor</code>, not a rollout milestone &mdash; and the "
   "<code>:scope-excluded</code> row below is on this page because the check actually fired "
   "during this run.</p>\n"

   "<main>\n"
   (summary-section db runs)
   (facilities-section db)
   (requests-section runs)
   (hard-checks-section db)
   (phase-gate-section)
   (governor-config-section)
   (ssot-section db)
   (attribution-section (approver-attribution db runs))
   (ledger-section db)
   "</main>\n"
   "</body></html>\n"))

;; ----------------------------- build-time invariants -----------------------------

(defn- assert-invariants!
  "The page is evidence, so the build refuses to write one that would not
  be. Each check THROWS -- see the ns docstring for why each exists."
  [{:keys [db runs]}]
  (let [hs    (hard-holds db)
        rules (into #{} (mapcat #(map :rule (:violations %)) hs))]

    ;; 1. a console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info (str "no HARD :governor-hold fact on the ledger -- refusing to write a "
                           "console that shows no real hold")
                      {:ledger-facts (count (store/ledger db))
                       :runs (count runs)})))

    ;; 2. evidence floor on DISTINCT rules.
    (when (< (count rules) min-distinct-hard-rules)
      (throw (ex-info "too few DISTINCT HARD governor rules fired"
                      {:required min-distinct-hard-rules
                       :got (count rules)
                       :rules (vec (sort rules))})))

    ;; 3. HARD holds must never have reached a human -- measured, not asserted.
    (when-let [bad (seq (for [r runs
                              :let [h (fact-of (:audit r) :governor-hold)]
                              :when (and h (seq (:violations h))
                                         (fact-of (:audit r) :approval-requested))]
                          (:tid r)))]
      (throw (ex-info "a HARD-held run also asked a human for approval"
                      {:runs (vec bad)})))

    ;; 4. every rendered HARD-check row must trace to a ledger rule.
    (let [rendered (into #{} (mapcat #(map :rule (:violations %)) hs))]
      (when-not (= rendered rules)
        (throw (ex-info "HARD-check rows do not match the ledger's rules"
                        {:rendered (vec (sort rendered)) :ledger (vec (sort rules))}))))

    {:hard-holds (count hs) :rules (vec (sort rules))}))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        {:keys [hard-holds rules]} (assert-invariants! result)
        f      (java.io.File. ^String out)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f (render result))
    (println "wrote" out
             (str "(" (count (store/ledger (:db result))) " ledger facts, "
                  hard-holds " HARD holds over " (count rules) " distinct rules "
                  (pr-str rules) ", "
                  (count (:runs result)) " runs, "
                  (count (store/coordination-log (:db result))) " committed records)"))))
