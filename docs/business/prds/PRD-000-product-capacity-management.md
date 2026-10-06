# Problem statement
In any software application, manage its capacity is the most important aspect that will impact to the rest of the other activity. 
There are several questions a product manager need to awnser: 
- How to make product grow, not in terms of more features, it's more on the problem it can solve for our customer. 
- How would we apply "The jobs to be done framework" to evaluate the capacity.

## 1. Problem & Customer Understanding (the JTBD core)
These questions force PMs to articulate the job before any solution talk happens.
What "job" is the customer hiring our product to do? State it as: when [situation], I want to [motivation], so I can [expected outcome] — not as a feature.
What was the customer using before us (a competitor, a spreadsheet, a workaround, nothing)? What made them "fire" that solution?
What are the functional, emotional, and social dimensions of this job? (e.g., functional: reconcile invoices faster; emotional: feel less anxious about errors; social: look competent to their boss.)
What are the "forces of progress" at play — what's pushing them away from the old way, pulling them toward a new way, and what anxiety or habit is holding them back from switching?
Which underserved or overserved outcomes exist today? (Outcome-driven innovation: ask customers to rate each desired outcome by importance and satisfaction — the gap identifies opportunity.)
If this job disappeared tomorrow, what would the customer lose? If it were solved perfectly, what would they gain?
Are we solving the job end-to-end, or only a slice of it — and who/what handles the rest?
## 2. Growth Definition (problems solved, not features shipped)
What does "growth" mean this quarter — more customers with the same job, the same customers doing the job more often, or new adjacent jobs for existing customers?
For each proposed feature: which job does this serve, and does it make an existing job easier, faster, cheaper, or more reliable — or does it just add optionality?
What would we have to build if we removed the ability to add new features for six months, and only improved how well we do existing jobs?
Which customer segment is most underserved on this job today, and is that segment strategically worth serving?
What's the "minimum remarkable" version of solving this job well — not MVP as in barely-functional, but MVP as in the smallest thing customers would switch for?
If a competitor solved only this one job perfectly and nothing else, would they take our customers? Why or why not?
What signals tell us a customer has "hired" us successfully versus just adopted a feature (usage depth vs. breadth, retention, expansion, referral)?
## 3. Capacity/Capability Assessment (this is where architecture meets product)
This is the bridge area — translating job requirements into what the system must be capable of.
For the jobs we say we serve, what technical capacity (throughput, latency, concurrency, data volume, integration surface) is actually required to do them well — and do we have evidence, or assumption?
What is the current ceiling of our system for this job (max users, max transaction volume, max complexity of workflow) before it breaks or degrades?
Which jobs are we implicitly promising to support that we haven't capacity-tested?
If customer demand for this job doubled in three months, what breaks first — and is that a data problem, an architecture problem, or a team/process problem?
What's the cost (engineering time, infra, ops) of serving this job at current quality per customer? Does that scale sub-linearly, linearly, or worse as adoption grows?
Are there jobs we're capable of solving technically but choose not to (strategic capacity) versus jobs we cannot solve regardless of will (technical capacity)?
What's our "capacity debt" — places where we're solving the job today via manual workarounds, one-off scripts, or unsustainable architecture that won't survive scale?
## 4. Prioritization & Trade-offs
When two jobs compete for the same engineering capacity, what's the deciding criterion — revenue impact, strategic fit, customer segment value, or urgency of the underserved outcome?
What are we explicitly choosing not to solve right now, and have we told customers that clearly?
Is this request a new job, a variant of an existing job, or a symptom of us not solving an existing job well enough?
What's the smallest capacity investment that unlocks the largest number of underserved outcomes?
If we had to cut 30% of the roadmap, which jobs would we protect, and why?
## 5. Metrics & Validation
How will we know, in customer language (not usage logs alone), that the job is being done better?
What outcome metric proxies the job's success (time saved, errors reduced, decisions made faster) rather than a vanity usage metric (logins, clicks)?
What would disprove our theory that this is the right job to focus on?
Are we measuring adoption of a feature or completion of a job?
What's the churn/switch signal — do customers leave because the job isn't done well, or because a better "hire" appeared elsewhere?
## 6. Roadmap & Strategic Alignment
Does our roadmap read as a list of jobs solved for customers, or a list of features shipped for stakeholders?
Which jobs are core to our identity (we must own this) versus peripheral (fine to solve adequately, not exceptionally)?
How does solving this job today change what capacity we'll need in 12–18 months — are we building toward that or against it?
If a new competitor optimized purely around this one job, what would they build differently than us — and should we?
A practical way to use this: run sections 1–2 with the PM to get alignment on why, then bring sections 3–4 into planning as the engineering counterpart — that's where you get to insist capacity/capability questions are answered with evidence, not assumption, before commitments are made. Section 5–6 keeps both sides honest afterward.
Want me to turn this into a working document (e.g., a JTBD interview/discovery template, or a capacity-planning canvas you could actually run in a workshop with PMs)?