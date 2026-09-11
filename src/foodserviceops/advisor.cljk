(ns foodserviceops.advisor
  "FoodServiceAdvisor -- the *contained intelligence node* for the
  ISIC-5629 institutional food-service operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: service-record logging, service-operation scheduling,
  supply-order coordination, and food-safety-concern flagging. CRITICAL:
  it is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER a
  direct actuation -- every proposal's `:effect` is always `:propose`.
  Every output is censored downstream by `foodserviceops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts a food-safety-clearance decision, an
  allergen-exclusion-requirement override, direct kitchen-equipment
  actuation, or any other food-safety-authority action (health-department
  clearance, inspection sign-off, permit/license decisions) -- those are
  permanently out of scope for this actor, not merely un-implemented.
  `foodserviceops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised or
  confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :facility-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-service-record
  "Draft a service-record log entry. Pure logging of observed
  operations (meal counts, menu served, allergen flags on the tray line)
  -- never a food-safety-clearance judgement."
  [_db {:keys [facility-id patch]}]
  {:op         :log-service-record
   :facility-id facility-id
   :summary    (str facility-id " の提供記録を記録: " (pr-str (keys patch)))
   :rationale  "提供食数・メニュー・アレルゲン表示の観察記録のみ。食品安全認可の判断なし。"
   :cites      [facility-id]
   :effect     :propose
   :value      (merge {:facility-id facility-id} patch)
   :confidence 0.93})

(defn- propose-service-operation
  "Draft a prep/service operation scheduling proposal (a calendar/roster
  entry, never a direct kitchen-equipment actuation)."
  [_db {:keys [facility-id patch]}]
  {:op         :schedule-service-operation
   :facility-id facility-id
   :summary    (str facility-id " の調理/提供オペレーション予定を提案: " (pr-str (keys patch)))
   :rationale  "仕込み・提供スケジュールの調整提案のみ。厨房設備の直接操作は行わない。"
   :cites      [facility-id]
   :effect     :propose
   :value      (merge {:facility-id facility-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft an ingredient/equipment procurement coordination request (food
  stock, disposable service-ware, small kitchen equipment -- never a
  finalized purchase order; a human always confirms procurement)."
  [_db {:keys [facility-id patch]}]
  {:op         :coordinate-supply-order
   :facility-id facility-id
   :summary    (str facility-id " に関連する食材/什器調達オーダーを提案: " (pr-str (keys patch)))
   :rationale  "食材・調理器具・使い捨て什器などの調達調整提案のみ。確定発注は人間が行う。"
   :cites      [facility-id]
   :effect     :propose
   :value      (merge {:facility-id facility-id} patch)
   :confidence 0.90})

(defn- propose-food-safety-concern
  "Surface a food-safety concern (allergen mismatch, temperature abuse,
  suspected contamination) for HUMAN triage. This op ALWAYS escalates in
  `foodserviceops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real."
  [_db {:keys [facility-id patch]}]
  {:op         :flag-food-safety-concern
   :facility-id facility-id
   :summary    (str facility-id " の食品安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "アレルゲン不一致・温度逸脱・異物混入疑い等の観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [facility-id]
   :effect     :propose
   :value      (merge {:facility-id facility-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-service-record (propose-service-record _db request)
                   :schedule-service-operation (propose-service-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-food-safety-concern (propose-food-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the clearance decision and applied an allergen exclusion override")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :facility-id (:facility-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
