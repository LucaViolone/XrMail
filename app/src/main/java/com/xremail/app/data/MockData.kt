package com.xremail.app.data

object MockData {

    val contacts = mapOf(
        "dr.chen@mit.edu" to Contact(
            name = "Dr. Wei Chen",
            email = "dr.chen@mit.edu",
            title = "Professor of Computer Science",
            organization = "MIT CSAIL",
            avatarInitials = "WC",
        ),
        "sarah.johnson@company.com" to Contact(
            name = "Sarah Johnson",
            email = "sarah.johnson@company.com",
            title = "Engineering Manager",
            organization = "Acme Corp",
            avatarInitials = "SJ",
        ),
        "team@slack.com" to Contact(
            name = "Team Slack",
            email = "team@slack.com",
            title = "Workspace Notifications",
            organization = "Slack",
            avatarInitials = "TS",
        ),
        "alex.rivera@startup.io" to Contact(
            name = "Alex Rivera",
            email = "alex.rivera@startup.io",
            title = "Co-founder & CTO",
            organization = "NeuralPath AI",
            avatarInitials = "AR",
        ),
        "grants@nsf.gov" to Contact(
            name = "NSF Grants Office",
            email = "grants@nsf.gov",
            title = "Program Director",
            organization = "National Science Foundation",
            avatarInitials = "NS",
        ),
        "newsletter@techdigest.com" to Contact(
            name = "Tech Digest",
            email = "newsletter@techdigest.com",
            title = "Weekly Newsletter",
            organization = "Tech Digest Media",
            avatarInitials = "TD",
        ),
        "promo@cloudservice.com" to Contact(
            name = "CloudPlatform",
            email = "promo@cloudservice.com",
            title = "Marketing",
            organization = "CloudPlatform Inc.",
            avatarInitials = "CP",
        ),
        "maria.santos@university.edu" to Contact(
            name = "Maria Santos",
            email = "maria.santos@university.edu",
            title = "PhD Candidate",
            organization = "Stanford NLP Lab",
            avatarInitials = "MS",
        ),
    )

    val emails = listOf(
        Email(
            id = "1",
            sender = "Dr. Wei Chen",
            senderEmail = "dr.chen@mit.edu",
            subject = "Paper revision — methodology section",
            body = """Hi,

Thank you for your submission to the conference proceedings. After reviewing the paper with the committee, we'd like to request some revisions to the methodology section.

Specifically:
1. The control group setup needs more detail — please describe the participant selection criteria.
2. The statistical analysis in Section 3.2 should include confidence intervals.
3. Consider adding a comparison with the baseline model from Zhang et al. (2025).

The revised deadline is this Friday at 5 PM EST. Please submit through the portal.

Looking forward to the updated version.

Best regards,
Dr. Wei Chen
MIT CSAIL""",
            timestamp = "10:32 AM",
            priority = Priority.HIGH,
            category = EmailCategory.PEOPLE,
            action = EmailAction.NEEDS_REPLY,
            isRead = false,
            isStarred = true,
            aiSummary = "Requests changes to methodology section by Friday. Suggests adding control group details, confidence intervals, and baseline comparison.",
            urgencyScore = 0.92f,
            suggestedReply = "Thank you for the feedback, Dr. Chen. I'll revise the methodology section with the requested changes and submit by Friday.",
            replyConfidence = 0.88f,
            actionItems = listOf(
                ActionItem("a1", "Add control group participant selection criteria", false),
                ActionItem("a2", "Include confidence intervals in Section 3.2", false),
                ActionItem("a3", "Add Zhang et al. (2025) baseline comparison", false),
                ActionItem("a4", "Submit revised paper by Friday 5 PM EST", false),
            ),
            attachments = listOf(
                Attachment("paper_v3.pdf", "PDF", "2.4 MB"),
                Attachment("review_comments.pdf", "PDF", "340 KB"),
            ),
            threadCount = 12,
        ),
        Email(
            id = "2",
            sender = "Sarah Johnson",
            senderEmail = "sarah.johnson@company.com",
            subject = "Sprint planning — Q2 priorities",
            body = """Hey team,

Quick reminder that our Q2 sprint planning is tomorrow at 2 PM in the main conference room (or virtual — link below).

Please come prepared with:
- Your top 3 priorities for the quarter
- Any blockers from last sprint
- Resource requests

Zoom link: https://zoom.us/j/123456789

See you there!
Sarah""",
            timestamp = "9:15 AM",
            priority = Priority.MEDIUM,
            category = EmailCategory.PEOPLE,
            action = EmailAction.READ_SUMMARY,
            isRead = false,
            aiSummary = "Sprint planning meeting tomorrow at 2 PM. Bring top 3 Q2 priorities, blockers, and resource requests.",
            urgencyScore = 0.65f,
            suggestedReply = "Thanks Sarah, I'll have my priorities ready. See you tomorrow!",
            replyConfidence = 0.75f,
            actionItems = listOf(
                ActionItem("b1", "Prepare top 3 Q2 priorities", false),
                ActionItem("b2", "List any blockers from last sprint", false),
            ),
            threadCount = 3,
        ),
        Email(
            id = "3",
            sender = "Team Slack",
            senderEmail = "team@slack.com",
            subject = "Meeting notes from #research-sync",
            body = """Here are the meeting notes from today's #research-sync channel:

Attendees: Wei, Alex, Maria, Jordan
Duration: 45 minutes

Key decisions:
- Moving forward with transformer-based approach for phase 2
- Alex will own the data pipeline refactor
- Target completion: end of April

Action items captured in the thread.""",
            timestamp = "Yesterday",
            priority = Priority.LOW,
            category = EmailCategory.UPDATES,
            action = EmailAction.READ_SUMMARY,
            isRead = true,
            aiSummary = "Research sync meeting notes. Team chose transformer approach for phase 2, Alex owns data pipeline. Target: end of April.",
            urgencyScore = 0.3f,
            threadCount = 1,
        ),
        Email(
            id = "4",
            sender = "Alex Rivera",
            senderEmail = "alex.rivera@startup.io",
            subject = "Partnership proposal — NeuralPath × Your Lab",
            body = """Hi,

I hope this email finds you well. I'm Alex Rivera, CTO at NeuralPath AI. We've been following your recent publications on spatial computing interfaces and we believe there's a strong alignment with our work on adaptive UI systems.

We'd love to explore a research partnership that could include:
- Joint paper submission to CHI 2027
- Access to our proprietary dataset (500K+ spatial interaction logs)
- Potential funding for a graduate researcher

Would you be open to a 30-minute call next week to discuss?

Best,
Alex Rivera
Co-founder & CTO, NeuralPath AI""",
            timestamp = "Yesterday",
            priority = Priority.HIGH,
            category = EmailCategory.PEOPLE,
            action = EmailAction.NEEDS_REPLY,
            isRead = false,
            aiSummary = "Partnership proposal from NeuralPath AI CTO. Offers joint CHI 2027 paper, proprietary dataset access, and grad researcher funding. Requesting a call next week.",
            urgencyScore = 0.85f,
            suggestedReply = "Hi Alex, thank you for reaching out. The partnership sounds very interesting — I'd be happy to schedule a call next week. How does Tuesday or Wednesday afternoon work?",
            replyConfidence = 0.82f,
            actionItems = listOf(
                ActionItem("d1", "Schedule call with Alex Rivera next week", false),
                ActionItem("d2", "Review NeuralPath AI publications", false),
            ),
            threadCount = 1,
        ),
        Email(
            id = "5",
            sender = "NSF Grants Office",
            senderEmail = "grants@nsf.gov",
            subject = "Grant #2024-XR-0847 — Annual progress report due",
            body = """Dear Principal Investigator,

This is a reminder that your annual progress report for Grant #2024-XR-0847 "Spatial Computing Interfaces for Accessible Education" is due on April 15, 2026.

Please submit through Research.gov and include:
- Research accomplishments
- Publications and presentations
- Student training activities
- Budget expenditure summary

Failure to submit on time may affect future funding.

NSF Grants Office""",
            timestamp = "2 days ago",
            priority = Priority.HIGH,
            category = EmailCategory.UPDATES,
            action = EmailAction.READ_FULL,
            isRead = true,
            isStarred = true,
            aiSummary = "NSF annual progress report due April 15. Submit via Research.gov with accomplishments, publications, student activities, and budget summary.",
            urgencyScore = 0.88f,
            actionItems = listOf(
                ActionItem("e1", "Compile research accomplishments for report", false),
                ActionItem("e2", "Gather publications and presentations list", false),
                ActionItem("e3", "Summarize student training activities", false),
                ActionItem("e4", "Prepare budget expenditure summary", false),
                ActionItem("e5", "Submit progress report on Research.gov by April 15", false),
            ),
            threadCount = 2,
        ),
        Email(
            id = "6",
            sender = "Tech Digest",
            senderEmail = "newsletter@techdigest.com",
            subject = "Weekly roundup: Android XR SDK hits stable, Apple Vision Pro 2 leaks",
            body = """This week in tech:

• Android XR SDK reaches 1.0 stable — developers can now publish to Galaxy XR
• Apple Vision Pro 2 rumored for Fall 2026 with lighter design
• Meta announces Quest 4 with full hand tracking
• New Kotlin 2.2 features for Compose Multiplatform
• OpenAI releases GPT-5.4 with improved spatial reasoning

Read the full articles at techdigest.com""",
            timestamp = "3 days ago",
            priority = Priority.LOW,
            category = EmailCategory.NEWSLETTERS,
            action = EmailAction.AUTO_ARCHIVE,
            isRead = true,
            aiSummary = "Weekly tech news: Android XR SDK stable release, Apple Vision Pro 2 rumors, Quest 4 announcement, Kotlin 2.2, GPT-5.4.",
            urgencyScore = 0.1f,
            threadCount = 1,
        ),
        Email(
            id = "7",
            sender = "CloudPlatform",
            senderEmail = "promo@cloudservice.com",
            subject = "Upgrade to Pro — 50% off for academic accounts",
            body = """Limited time offer for academic institutions!

Upgrade your CloudPlatform account to Pro and get:
- Unlimited GPU compute hours
- Priority support
- Advanced analytics dashboard

Use code ACADEMIC50 at checkout.

Offer expires March 31, 2026.""",
            timestamp = "3 days ago",
            priority = Priority.IGNORE,
            category = EmailCategory.PROMOTIONS,
            action = EmailAction.AUTO_ARCHIVE,
            isRead = true,
            aiSummary = "CloudPlatform Pro 50% academic discount. Code: ACADEMIC50. Expires March 31.",
            urgencyScore = 0.05f,
            threadCount = 1,
        ),
        Email(
            id = "8",
            sender = "Maria Santos",
            senderEmail = "maria.santos@university.edu",
            subject = "Question about your UIST 2025 paper",
            body = """Dear Professor,

I'm Maria Santos, a PhD candidate at Stanford working on natural language interfaces for spatial computing. I read your UIST 2025 paper on gaze-driven email triage and found it fascinating.

I had a few questions:
1. How did you handle calibration drift during long sessions?
2. Did you test with users who wear corrective lenses?
3. Would you be open to sharing the evaluation protocol?

I'm working on a related project and your insights would be incredibly valuable.

Thank you for your time,
Maria Santos
Stanford NLP Lab""",
            timestamp = "4 days ago",
            priority = Priority.MEDIUM,
            category = EmailCategory.PEOPLE,
            action = EmailAction.NEEDS_REPLY,
            isRead = false,
            aiSummary = "Stanford PhD student asking about your UIST 2025 gaze-driven email triage paper. Questions about calibration drift, corrective lenses, and evaluation protocol sharing.",
            urgencyScore = 0.55f,
            suggestedReply = "Hi Maria, thank you for your interest in the paper! I'd be happy to answer your questions. Let me address each one...",
            replyConfidence = 0.70f,
            actionItems = listOf(
                ActionItem("h1", "Reply to Maria Santos about UIST paper questions", false),
            ),
            threadCount = 1,
        ),
    )

    fun getContactForEmail(email: Email): Contact {
        return contacts[email.senderEmail] ?: Contact(
            name = email.sender,
            email = email.senderEmail,
            title = "",
            organization = "",
            avatarInitials = email.sender.split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString(""),
        )
    }
}
