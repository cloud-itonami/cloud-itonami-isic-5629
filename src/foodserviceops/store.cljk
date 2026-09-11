(ns foodserviceops.store
  "SSoT for the ISIC-5629 institutional food-service COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of institutional /
  contract food-service sites -- hospital cafeterias, school canteens,
  food-court vendor bays, and similar 'other food service activities'
  under institutional contracts (ISIC Rev.4 5629; a residual category
  distinct from stand-alone restaurants/mobile food service (5610) and
  from stand-alone event catering (5621)): meal-count/menu/allergen-flag
  logging, prep/service scheduling, ingredient/equipment supply-order
  coordination, and food-safety-concern flagging. It never finalizes a
  food-safety-clearance decision, never overrides an allergen-exclusion
  requirement, and never directly actuates kitchen equipment -- see
  `foodserviceops.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `facilities` directory keyed by `:facility-id` STRING
  (never a keyword -- consistent keying from the start, avoiding the
  silent-miss bug that plagued an earlier shepherd attempt).

  A registered/verified facility (service-contract) record must exist
  before ANY proposal for that facility may ever commit or escalate --
  `foodserviceops.governor`'s `facility-unverified-violations` re-derives
  this from the facility's own `:registered?`/`:verified?` fields, never
  from proposal self-report, the SAME 'ground truth, not self-report'
  discipline every sibling actor's own governor uses.

  The ledger stays append-only: which facility a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (facility [s facility-id] "Registered facility record, or nil.
    Facility map: {:facility-id .. :name .. :registered? bool :verified? bool}.")
  (all-facilities [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-facilities [s facilities] "replace/seed the facility directory (map facility-id->facility)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:facilities
   {"facility-1" {:facility-id "facility-1" :name "Riverside Hospital Cafeteria"
                   :registered? true :verified? true}
    "facility-2" {:facility-id "facility-2" :name "Lincoln High School Canteen"
                   :registered? true :verified? true}
    "facility-3" {:facility-id "facility-3" :name "Downtown Office Tower Food-Court Vendor Bay 4 (in intake)"
                   :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility [_ facility-id] (get-in @a [:facilities facility-id]))
  (all-facilities [_] (sort-by :facility-id (vals (:facilities @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s))

(defn seed-db
  "A MemStore seeded with the demo facility directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `facilities` map (facility-id string
  -> facility map) -- the primary test/dev entry point. `facilities` may
  be empty (an unregistered-everywhere store)."
  [facilities]
  (->MemStore (atom {:facilities (or facilities {}) :ledger [] :coordination-log []})))
