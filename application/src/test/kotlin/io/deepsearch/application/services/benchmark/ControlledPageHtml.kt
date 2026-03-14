package io.deepsearch.application.services.benchmark

/**
 * Shared HTML fixtures for controlled benchmark pages.
 * These are the same pages used by AgenticWebpageSearchServiceTest,
 * extracted here so both the existing tests and the benchmark framework
 * can reference them.
 */
object ControlledPageHtml {

    val VISIBLE_ANSWER_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head><title>About Us</title></head>
        <body>
            <h1>About Our Company</h1>
            <p>We are a technology company founded in 2020.</p>
            <p>Our company motto is: <strong>INNOVATION-DRIVES-PROGRESS</strong></p>
            <p>We serve customers in over 50 countries.</p>
        </body>
        </html>
    """.trimIndent()

    val ACCORDION_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>FAQ</title>
            <style>
                body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; }
                .faq-item { border: 1px solid #ccc; margin: 10px 0; border-radius: 8px; }
                .faq-question {
                    padding: 16px; cursor: pointer; background: #f5f5f5;
                    border: none; width: 100%; text-align: left; font-size: 16px;
                    border-radius: 8px;
                }
                .faq-question:hover { background: #e8e8e8; }
                .faq-answer { padding: 16px; border-top: 1px solid #eee; }
            </style>
        </head>
        <body>
            <h1>Frequently Asked Questions</h1>
            <p>Click on a question to see the answer.</p>
            <div class="faq-item">
                <button class="faq-question" onclick="toggleAnswer('a1')">What are your business hours?</button>
                <div id="a1" class="faq-answer" style="display:none">We are open Monday through Friday, 9 AM to 5 PM EST.</div>
            </div>
            <div class="faq-item">
                <button class="faq-question" onclick="toggleAnswer('a2')">What is your refund policy?</button>
                <div id="a2" class="faq-answer" style="display:none">Our refund policy code is <strong>REFUND-GAMMA-77</strong>. You can request a full refund within 30 days of purchase.</div>
            </div>
            <div class="faq-item">
                <button class="faq-question" onclick="toggleAnswer('a3')">How do I contact support?</button>
                <div id="a3" class="faq-answer" style="display:none">Email us at support@example.com or call 1-800-EXAMPLE.</div>
            </div>
            <script>
                function toggleAnswer(id) {
                    var el = document.getElementById(id);
                    el.style.display = el.style.display === 'none' ? 'block' : 'none';
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    val TAB_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>Pricing</title>
            <style>
                body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; }
                .tab-bar { display: flex; gap: 0; border-bottom: 2px solid #333; }
                .tab-btn {
                    padding: 12px 24px; cursor: pointer; background: #f0f0f0;
                    border: 1px solid #ccc; border-bottom: none; font-size: 16px;
                }
                .tab-btn.active { background: white; font-weight: bold; border-bottom: 2px solid white; margin-bottom: -2px; }
                .tab-panel { padding: 24px; border: 1px solid #ccc; border-top: none; }
            </style>
        </head>
        <body>
            <h1>Our Pricing Plans</h1>
            <p>Choose the plan that fits your needs.</p>
            <div class="tab-bar">
                <button class="tab-btn active" onclick="showTab('starter')">Starter</button>
                <button class="tab-btn" onclick="showTab('professional')">Professional</button>
                <button class="tab-btn" onclick="showTab('enterprise')">Enterprise</button>
            </div>
            <div id="tab-starter" class="tab-panel">
                <h2>Starter Plan</h2>
                <p>Price: <strong>${'$'}29/month</strong></p>
                <p>Includes 5 users, 10GB storage, email support.</p>
            </div>
            <div id="tab-professional" class="tab-panel" style="display:none">
                <h2>Professional Plan</h2>
                <p>Price: <strong>${'$'}99/month</strong></p>
                <p>Includes 25 users, 100GB storage, priority support.</p>
            </div>
            <div id="tab-enterprise" class="tab-panel" style="display:none">
                <h2>Enterprise Plan</h2>
                <p>Price: <strong>${'$'}499/month</strong></p>
                <p>Includes unlimited users, 1TB storage, dedicated account manager, SLA guarantee.</p>
            </div>
            <script>
                function showTab(name) {
                    document.querySelectorAll('.tab-panel').forEach(function(p) { p.style.display = 'none'; });
                    document.querySelectorAll('.tab-btn').forEach(function(b) { b.classList.remove('active'); });
                    document.getElementById('tab-' + name).style.display = 'block';
                    event.target.classList.add('active');
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    val DEEP_ACCORDION_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>Developer Documentation</title>
            <style>
                body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; }
                .section { border: 1px solid #ddd; margin: 12px 0; border-radius: 8px; }
                .section-header {
                    padding: 14px 18px; cursor: pointer; background: #f7f7f7;
                    border: none; width: 100%; text-align: left; font-size: 16px;
                    font-weight: bold;
                }
                .section-body { padding: 18px; display: none; border-top: 1px solid #eee; }
                .subsection { border: 1px solid #e0e0e0; margin: 10px 0; border-radius: 6px; }
                .subsection-header {
                    padding: 10px 14px; cursor: pointer; background: #fafafa;
                    border: none; width: 100%; text-align: left; font-size: 14px;
                }
                .subsection-body { padding: 14px; display: none; border-top: 1px solid #f0f0f0; }
            </style>
        </head>
        <body>
            <h1>Developer Documentation</h1>
            <p>Expand sections below to find what you need.</p>
            <div class="section">
                <button class="section-header" onclick="toggle(this)">Getting Started</button>
                <div class="section-body"><p>Follow our quickstart guide to set up your environment.</p></div>
            </div>
            <div class="section">
                <button class="section-header" onclick="toggle(this)">Authentication &amp; API Keys</button>
                <div class="section-body">
                    <p>This section covers API key management.</p>
                    <div class="subsection">
                        <button class="subsection-header" onclick="toggle(this)">How to generate an API key</button>
                        <div class="subsection-body"><p>Go to Settings &gt; API Keys &gt; Generate New Key.</p></div>
                    </div>
                    <div class="subsection">
                        <button class="subsection-header" onclick="toggle(this)">What is the secret API key?</button>
                        <div class="subsection-body">
                            <p>The secret API key for the sandbox environment is: <strong>SK-DEEP-NESTED-42X</strong></p>
                            <p>Keep this key confidential. Do not share it publicly.</p>
                        </div>
                    </div>
                    <div class="subsection">
                        <button class="subsection-header" onclick="toggle(this)">Rate limits</button>
                        <div class="subsection-body"><p>Free tier: 100 requests/min. Pro tier: 10,000 requests/min.</p></div>
                    </div>
                </div>
            </div>
            <div class="section">
                <button class="section-header" onclick="toggle(this)">Webhooks</button>
                <div class="section-body"><p>Configure webhooks to receive real-time event notifications.</p></div>
            </div>
            <script>
                function toggle(btn) {
                    var body = btn.nextElementSibling;
                    body.style.display = body.style.display === 'none' || body.style.display === '' ? 'block' : 'none';
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    val NONEXISTENT_INFO_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>Bella Cucina — Italian Restaurant</title>
            <style>
                body { font-family: Georgia, serif; max-width: 800px; margin: 40px auto; padding: 0 24px; color: #333; }
                h1 { font-size: 28px; }
                .info-block { background: #faf7f2; border: 1px solid #e0d6c8; padding: 20px; border-radius: 8px; margin: 20px 0; }
                .info-block p { margin: 6px 0; font-size: 15px; }
                .faq-item { border: 1px solid #ddd; margin: 8px 0; border-radius: 6px; }
                .faq-q { padding: 14px 18px; cursor: pointer; background: #fafafa; border: none; width: 100%; text-align: left; font-size: 15px; }
                .faq-q:hover { background: #f0f0f0; }
                .faq-a { padding: 14px 18px; border-top: 1px solid #eee; font-size: 14px; line-height: 1.6; }
            </style>
        </head>
        <body>
            <h1>Bella Cucina</h1>
            <p>Authentic Italian dining in the heart of downtown since 1998.</p>
            <div class="info-block">
                <p><strong>Address:</strong> 742 Evergreen Terrace, Suite 4, Springfield, IL 62704</p>
                <p><strong>Phone:</strong> (217) 555-0142</p>
                <p><strong>Hours:</strong> Tue–Sun 11:30 AM – 10:00 PM, Closed Mondays</p>
                <p><strong>Reservations:</strong> Required for parties of 6+</p>
            </div>
            <h2>Our Menu Highlights</h2>
            <ul>
                <li>Truffle Risotto — ${'$'}24</li>
                <li>Osso Buco Milanese — ${'$'}32</li>
                <li>Margherita Pizza (wood-fired) — ${'$'}16</li>
                <li>Tiramisu — ${'$'}10</li>
            </ul>
            <h2>Frequently Asked Questions</h2>
            <div class="faq-item">
                <button class="faq-q" onclick="toggle(this)">Do you accommodate dietary restrictions?</button>
                <div class="faq-a" style="display:none">Yes. We offer gluten-free pasta and several vegan entrées. Please inform your server of any allergies.</div>
            </div>
            <div class="faq-item">
                <button class="faq-q" onclick="toggle(this)">Is there parking available?</button>
                <div class="faq-a" style="display:none">Free street parking is available on Evergreen Terrace. A paid garage is located one block east on Elm Street (${'$'}5 flat rate evenings).</div>
            </div>
            <div class="faq-item">
                <button class="faq-q" onclick="toggle(this)">Do you host private events?</button>
                <div class="faq-a" style="display:none">Our private dining room seats up to 30 guests. Contact events@bellacucina.example.com for availability and pricing.</div>
            </div>
            <p style="margin-top: 32px; color: #888; font-size: 12px;">&copy; 2024 Bella Cucina LLC. All rights reserved.</p>
            <script>
                function toggle(btn) {
                    var a = btn.nextElementSibling;
                    a.style.display = a.style.display === 'none' ? 'block' : 'none';
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    val NUMBER_DENSE_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>Product Catalog</title>
            <style>
                body { font-family: Arial, sans-serif; max-width: 900px; margin: 40px auto; }
                table { width: 100%; border-collapse: collapse; margin: 20px 0; }
                th, td { border: 1px solid #ddd; padding: 12px 16px; text-align: left; }
                th { background: #f5f5f5; }
                .product-id { font-weight: bold; color: #333; }
                .price { font-size: 18px; color: #b12704; font-weight: bold; }
                .stock { color: #007600; }
                .rating { color: #e47911; }
                .btn { padding: 8px 16px; background: #0066c0; color: white; border: none; cursor: pointer; border-radius: 4px; }
                .details { padding: 16px; background: #f9f9f9; border: 1px solid #ddd; margin: 8px 0; }
            </style>
        </head>
        <body>
            <h1>Electronics Product Catalog</h1>
            <p>Showing 5 of 1,247 products. Page 3 of 125. Order #90847.</p>
            <table>
                <tr><th>ID</th><th>Product</th><th>Price</th><th>Stock</th><th>Rating</th><th>SKU</th></tr>
                <tr><td class="product-id">#401</td><td>UltraView Monitor 27"</td><td class="price">${'$'}1,299</td><td class="stock">847 in stock</td><td class="rating">4.7/5 (2,341 reviews)</td><td>SKU-90124</td></tr>
                <tr><td class="product-id">#402</td><td>SpeedType Keyboard</td><td class="price">${'$'}189</td><td class="stock">3,421 in stock</td><td class="rating">4.5/5 (892 reviews)</td><td>SKU-90125</td></tr>
                <tr><td class="product-id">#403</td><td>ProMax Laptop 15"</td><td class="price">${'$'}2,499</td><td class="stock">156 in stock</td><td class="rating">4.8/5 (5,671 reviews)</td><td>SKU-90126</td></tr>
                <tr><td class="product-id">#404</td><td>SoundPro Headphones</td><td class="price">${'$'}349</td><td class="stock">2,108 in stock</td><td class="rating">4.6/5 (1,234 reviews)</td><td>SKU-90127</td></tr>
                <tr><td class="product-id">#405</td><td>PowerDock Station</td><td class="price">${'$'}279</td><td class="stock">967 in stock</td><td class="rating">4.3/5 (456 reviews)</td><td>SKU-90128</td></tr>
            </table>
            <p>Reference codes: Region 874, Warehouse 1055, Batch 2209, Shipment 663.</p>
            <div>
                <button class="btn" onclick="document.getElementById('details-403').style.display = document.getElementById('details-403').style.display === 'none' ? 'block' : 'none'">
                    Show ProMax Warranty Details
                </button>
                <div id="details-403" class="details" style="display:none">
                    <h3>ProMax Laptop 15" — Warranty Information</h3>
                    <p>Standard warranty period: <strong>36 months</strong> from date of purchase.</p>
                    <p>Extended warranty available: 60 months for ${'$'}199. Coverage ID: WRN-90403.</p>
                </div>
            </div>
            <p style="margin-top: 40px; color: #666; font-size: 12px;">
                Customer service: 1-800-555-0199 | Fax: 1-800-555-0200 | Store #1042, Mall Level 3, Unit 874-B
            </p>
        </body>
        </html>
    """.trimIndent()

    val CROWDED_LABELS_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>Acme Corp — Plans &amp; FAQ</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 0; }
                .layout { display: flex; max-width: 1200px; margin: 0 auto; }
                nav { background: #1a1a2e; color: white; padding: 8px 24px; display: flex; gap: 16px; align-items: center; }
                nav a { color: #ccc; text-decoration: none; font-size: 14px; }
                .sidebar { width: 220px; padding: 16px; border-right: 1px solid #ddd; font-size: 13px; }
                .sidebar a { display: block; padding: 6px 0; color: #0066c0; text-decoration: none; }
                .main { flex: 1; padding: 24px; }
                table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 14px; }
                th, td { border: 1px solid #ddd; padding: 10px; text-align: center; }
                th { background: #f5f5f5; }
                .faq-item { border: 1px solid #ccc; margin: 8px 0; border-radius: 6px; }
                .faq-q { padding: 12px 16px; cursor: pointer; background: #f8f8f8; border: none; width: 100%; text-align: left; font-size: 14px; }
                .faq-a { padding: 12px 16px; border-top: 1px solid #eee; }
                .badge { display: inline-block; background: #e74c3c; color: white; border-radius: 50%; width: 20px; height: 20px; text-align: center; line-height: 20px; font-size: 11px; margin-left: 4px; }
            </style>
        </head>
        <body>
            <nav><a href="#">Home</a><a href="#">Products</a><a href="#">Pricing</a><a href="#">Documentation</a><a href="#">Blog</a><a href="#">Support</a><a href="#">Contact</a><a href="#">Login</a></nav>
            <div class="layout">
                <div class="sidebar">
                    <strong>Quick Links</strong>
                    <a href="#">Overview (Section 1)</a><a href="#">Features (Section 2)</a><a href="#">Pricing (Section 3)</a>
                    <a href="#">Compare Plans (Section 4)</a><a href="#">FAQ (Section 5)</a><a href="#">Terms (Section 6)</a>
                    <a href="#">Privacy (Section 7)</a><a href="#">Refunds (Section 8)</a>
                </div>
                <div class="main">
                    <h1>Pricing Plans</h1>
                    <p>Over 15 million customers trust Acme Corp. Join plan #3 (our most popular) or explore all 8 options below.</p>
                    <table>
                        <tr><th>Feature</th><th>Basic (${'$'}9/mo)<br>Plan #1</th><th>Standard (${'$'}19/mo)<br>Plan #2</th><th>Premium (${'$'}49/mo)<br>Plan #3</th><th>Enterprise (${'$'}99/mo)<br>Plan #4</th></tr>
                        <tr><td>Users</td><td>1</td><td>5</td><td>15</td><td>Unlimited</td></tr>
                        <tr><td>Storage</td><td>5 GB</td><td>25 GB</td><td>100 GB</td><td>500 GB</td></tr>
                        <tr><td>API calls/day</td><td>100</td><td>1,000</td><td>10,000</td><td>Unlimited</td></tr>
                        <tr><td>Support</td><td>Email (48h)</td><td>Email (24h)</td><td>Priority (4h)</td><td>Dedicated (1h)</td></tr>
                        <tr><td>Uptime SLA</td><td>99%</td><td>99.5%</td><td>99.9%</td><td>99.99%</td></tr>
                    </table>
                    <p>Step 1: Choose your plan. Step 2: Enter billing info. Step 3: Start your 14-day trial. Offer code #12 expires Dec 31.</p>
                    <h2>Frequently Asked Questions <span class="badge">8</span></h2>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What payment methods do you accept?</button><div class="faq-a" style="display:none">We accept Visa, MasterCard, AMEX, and PayPal. Invoice available for Plan #4.</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">Can I switch plans mid-billing cycle?</button><div class="faq-a" style="display:none">Yes, upgrades take effect immediately. Downgrades apply at the next billing date. See Item 12 in our Terms of Service.</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What is the cancellation fee for the Premium plan?</button><div class="faq-a" style="display:none">The cancellation fee for the Premium plan (Plan #3) is <strong>${'$'}75</strong> if cancelled within the first 6 months. After 6 months, cancellation is free. Reference: Policy 15-C.</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">Is there a free trial?</button><div class="faq-a" style="display:none">All plans include a 14-day free trial. No credit card required for Basic (Plan #1).</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">Do you offer educational discounts?</button><div class="faq-a" style="display:none">Yes, 40% off for verified .edu email addresses. Apply via Form 7-B on our website.</div></div>
                    <p style="color: #888; font-size: 12px; margin-top: 32px;">Acme Corp — Founded 2019 — 8 offices worldwide — Support ticket average: 3.2 hours — Rating: 4.7/5 from 12,450 reviews — Tax ID: 15-8842091 — Page 1 of 3</p>
                </div>
            </div>
            <script>function toggle(btn) { var a = btn.nextElementSibling; a.style.display = a.style.display === 'none' ? 'block' : 'none'; }</script>
        </body>
        </html>
    """.trimIndent()

    val NAVIGATION_HEAVY_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>StrangerSoccer — Help Center</title>
            <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #333; }
                header { background: #1b2838; padding: 12px 24px; display: flex; align-items: center; justify-content: space-between; }
                header .logo { color: #4fc3f7; font-size: 20px; font-weight: bold; text-decoration: none; }
                header nav { display: flex; gap: 18px; }
                header nav a { color: #ccc; text-decoration: none; font-size: 14px; }
                .breadcrumb { background: #f0f0f0; padding: 10px 24px; font-size: 13px; }
                .breadcrumb a { color: #0066c0; text-decoration: none; }
                .layout { display: flex; max-width: 1200px; margin: 0 auto; min-height: 600px; }
                .sidebar { width: 240px; padding: 20px 16px; border-right: 1px solid #e0e0e0; }
                .sidebar h3 { font-size: 14px; margin-bottom: 12px; color: #555; text-transform: uppercase; }
                .sidebar a { display: block; padding: 7px 0; color: #0066c0; text-decoration: none; font-size: 14px; }
                .sidebar .active { font-weight: bold; color: #333; }
                .main { flex: 1; padding: 24px 32px; }
                .main h1 { font-size: 24px; margin-bottom: 8px; }
                .main .subtitle { color: #666; margin-bottom: 24px; font-size: 14px; }
                .faq-item { border: 1px solid #ddd; margin: 8px 0; border-radius: 6px; overflow: hidden; }
                .faq-q { padding: 14px 18px; cursor: pointer; background: #fafafa; border: none; width: 100%; text-align: left; font-size: 15px; display: flex; justify-content: space-between; align-items: center; }
                .faq-q:hover { background: #f0f0f0; }
                .faq-q::after { content: '+'; font-size: 18px; color: #888; }
                .faq-a { padding: 14px 18px; border-top: 1px solid #eee; font-size: 14px; line-height: 1.6; }
                footer { background: #1b2838; color: #aaa; padding: 24px; font-size: 13px; }
                footer .footer-links { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 12px; }
                footer a { color: #8bb4d9; text-decoration: none; }
            </style>
        </head>
        <body>
            <header><a class="logo" href="https://strangersoccer.com">StrangerSoccer</a><nav><a href="https://strangersoccer.com/games">Find Games</a><a href="https://strangersoccer.com/leagues">Leagues</a><a href="https://strangersoccer.com/venues">Venues</a><a href="https://strangersoccer.com/pricing">Pricing</a><a href="https://strangersoccer.com/about">About</a><a href="https://strangersoccer.com/blog">Blog</a><a href="https://strangersoccer.com/contact">Contact</a><a href="https://strangersoccer.com/login">Log In</a><a href="https://strangersoccer.com/signup">Sign Up</a></nav></header>
            <div class="breadcrumb"><a href="https://strangersoccer.com">Home</a> &gt; <a href="https://strangersoccer.com/help">Help Center</a> &gt; <span>Other</span></div>
            <div class="layout">
                <div class="sidebar"><h3>Help Categories</h3><a href="https://strangersoccer.com/help?category=getting-started">Getting Started</a><a href="https://strangersoccer.com/help?category=account">Account &amp; Profile</a><a href="https://strangersoccer.com/help?category=payments">Payments &amp; Billing</a><a href="https://strangersoccer.com/help?category=games">Games &amp; Scheduling</a><a href="https://strangersoccer.com/help?category=teams">Teams &amp; Leagues</a><a href="https://strangersoccer.com/help?category=venues">Venues &amp; Locations</a><a href="https://strangersoccer.com/help?category=safety">Safety &amp; Conduct</a><a href="https://strangersoccer.com/help?category=mobile">Mobile App</a><a href="https://strangersoccer.com/help?category=referrals">Referral Program</a><a class="active" href="#">Other</a></div>
                <div class="main">
                    <h1>Other Questions</h1>
                    <p class="subtitle">Can't find what you're looking for? Browse the topics below or contact support.</p>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">How do I delete my account?</button><div class="faq-a" style="display:none">Go to Settings &gt; Account &gt; Delete Account. You have 30 days to reactivate before data is permanently removed.</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What is the guest policy for games?</button><div class="faq-a" style="display:none">Each registered player may bring one guest per game at no extra charge. Guests must sign a waiver at the venue. The guest limit per game is capped at 3 across all players.</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What is the weather cancellation policy?</button><div class="faq-a" style="display:none">The weather cancellation policy code is <strong>WX-CANCEL-88R</strong>. Games are automatically cancelled if the National Weather Service issues a severe weather warning for the venue area within 2 hours of game time. All players receive a full credit to their account within 24 hours.</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">Can I change the game time after creation?</button><div class="faq-a" style="display:none">Game creators can reschedule up to 12 hours before kickoff. All registered players will be notified automatically. Rescheduling within 12 hours requires approval from at least 80% of registered players.</div></div>
                    <div class="faq-item"><button class="faq-q" onclick="toggle(this)">How do I report a bug or suggest a feature?</button><div class="faq-a" style="display:none">Email feedback@strangersoccer.com with the subject line "Bug Report" or "Feature Request". Include screenshots if applicable. We review all submissions within 48 hours.</div></div>
                </div>
            </div>
            <footer><div class="footer-links"><a href="https://strangersoccer.com/terms">Terms of Service</a><a href="https://strangersoccer.com/privacy">Privacy Policy</a><a href="https://strangersoccer.com/cookies">Cookie Policy</a><a href="https://strangersoccer.com/accessibility">Accessibility</a><a href="https://strangersoccer.com/careers">Careers</a><a href="https://strangersoccer.com/press">Press</a><a href="https://strangersoccer.com/investors">Investors</a></div><p>&copy; 2024 StrangerSoccer Inc. All rights reserved.</p></footer>
            <script>function toggle(btn) { var a = btn.nextElementSibling; a.style.display = a.style.display === 'none' ? 'block' : 'none'; }</script>
        </body>
        </html>
    """.trimIndent()

    val OFFPAGE_LOOP_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>CloudSync — Product Page</title>
            <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #333; }
                nav { background: #0d1b2a; padding: 12px 24px; display: flex; gap: 20px; align-items: center; }
                nav a { color: #8bb4d9; text-decoration: none; font-size: 14px; }
                nav .brand { color: #4fc3f7; font-weight: bold; font-size: 18px; }
                .hero { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; text-align: center; padding: 60px 24px; }
                .hero h1 { font-size: 36px; margin-bottom: 16px; }
                .hero p { font-size: 18px; margin-bottom: 24px; max-width: 600px; margin-left: auto; margin-right: auto; }
                .hero .cta { display: inline-block; background: #ff6b35; color: white; padding: 16px 48px; border-radius: 8px; text-decoration: none; font-size: 18px; font-weight: bold; }
                .hero .cta-secondary { display: inline-block; margin-left: 16px; color: white; padding: 16px 32px; border: 2px solid white; border-radius: 8px; text-decoration: none; font-size: 16px; }
                .features { display: flex; gap: 24px; max-width: 900px; margin: 40px auto; padding: 0 24px; }
                .feature-card { flex: 1; padding: 24px; border: 1px solid #e0e0e0; border-radius: 8px; text-align: center; }
                .feature-card h3 { margin-bottom: 8px; }
                .feature-card a { color: #667eea; text-decoration: none; font-size: 14px; }
                .faq-section { max-width: 700px; margin: 40px auto; padding: 0 24px; }
                .faq-section h2 { margin-bottom: 16px; font-size: 20px; }
                .faq-item { border: 1px solid #ddd; margin: 8px 0; border-radius: 6px; }
                .faq-q { padding: 14px 18px; cursor: pointer; background: #fafafa; border: none; width: 100%; text-align: left; font-size: 14px; }
                .faq-a { padding: 14px 18px; border-top: 1px solid #eee; font-size: 14px; }
            </style>
        </head>
        <body>
            <nav><a class="brand" href="https://cloudsync.example.com">CloudSync</a><a href="https://cloudsync.example.com/features">Features</a><a href="https://cloudsync.example.com/pricing">Pricing</a><a href="https://cloudsync.example.com/docs">Documentation</a><a href="https://cloudsync.example.com/blog">Blog</a><a href="https://cloudsync.example.com/login">Login</a></nav>
            <div class="hero"><h1>Sync Everything. Everywhere.</h1><p>CloudSync keeps your files, databases, and configurations in perfect harmony across all your environments.</p><a class="cta" href="https://cloudsync.example.com/signup">Start Free Trial</a><a class="cta-secondary" href="https://cloudsync.example.com/demo">Watch Demo</a></div>
            <div class="features"><div class="feature-card"><h3>Real-time Sync</h3><p>Sub-second propagation across regions.</p><a href="https://cloudsync.example.com/features/realtime">Learn more &rarr;</a></div><div class="feature-card"><h3>Conflict Resolution</h3><p>Automatic merge with manual override.</p><a href="https://cloudsync.example.com/features/conflicts">Learn more &rarr;</a></div><div class="feature-card"><h3>Enterprise Security</h3><p>SOC 2, HIPAA, and GDPR compliant.</p><a href="https://cloudsync.example.com/features/security">Learn more &rarr;</a></div></div>
            <div class="faq-section">
                <h2>Frequently Asked Questions</h2>
                <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What platforms are supported?</button><div class="faq-a" style="display:none">CloudSync supports macOS, Windows, Linux, iOS, and Android. Browser extension available for Chrome and Firefox.</div></div>
                <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What is the maximum file size for sync?</button><div class="faq-a" style="display:none">The maximum file size for sync is <strong>50 GB</strong> per file on all plans. The free tier has a total storage limit of 15 GB. Sync configuration code: <strong>SYNC-MAX-50G</strong>.</div></div>
                <div class="faq-item"><button class="faq-q" onclick="toggle(this)">How do I contact support?</button><div class="faq-a" style="display:none">Email support@cloudsync.example.com or use the in-app chat. Enterprise customers get dedicated Slack channels.</div></div>
            </div>
            <script>function toggle(btn) { var a = btn.nextElementSibling; a.style.display = a.style.display === 'none' ? 'block' : 'none'; }</script>
        </body>
        </html>
    """.trimIndent()

    val COOKIE_ONETRUST_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head><title>TechCorp — Company Info</title><style>body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; padding: 24px; }</style></head>
        <body>
            <h1>Welcome to TechCorp</h1>
            <p>We are a technology company providing innovative solutions.</p>
            <p>Our activation code is: <strong>ONETRUST-PASS-99</strong></p>
            <p>Contact us at info@techcorp.example.com for more information.</p>
            <div id="onetrust-consent-sdk" style="position:fixed;top:0;left:0;width:100%;height:100%;z-index:10000;">
                <div style="position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);"></div>
                <div style="position:fixed;bottom:0;left:0;width:100%;background:white;padding:24px;box-shadow:0 -2px 10px rgba(0,0,0,0.2);z-index:10001;">
                    <h3>We value your privacy</h3>
                    <p style="font-size:14px;color:#555;margin:12px 0;">We use cookies to enhance your browsing experience, serve personalized ads or content, and analyze our traffic.</p>
                    <div style="display:flex;gap:12px;margin-top:16px;">
                        <button id="onetrust-accept-btn-handler" onclick="document.getElementById('onetrust-consent-sdk').style.display='none'" style="background:#1f96f3;color:white;border:none;padding:12px 32px;border-radius:4px;cursor:pointer;font-size:14px;">Accept All</button>
                        <button style="background:white;border:1px solid #ccc;padding:12px 32px;border-radius:4px;cursor:pointer;font-size:14px;">Reject All</button>
                        <button style="background:white;border:1px solid #ccc;padding:12px 32px;border-radius:4px;cursor:pointer;font-size:14px;">Customize Settings</button>
                    </div>
                </div>
            </div>
        </body>
        </html>
    """.trimIndent()

    val COOKIE_COOKIEBOT_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head><title>DataHub — Service Info</title><style>body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; padding: 24px; }</style></head>
        <body>
            <h1>Welcome to DataHub Services</h1>
            <p>Your trusted data processing partner since 2018.</p>
            <p>The service activation key is: <strong>COOKIEBOT-PASS-55</strong></p>
            <p>For enterprise inquiries, contact sales@datahub.example.com.</p>
            <div id="CybotCookiebotDialogBodyUnderlay" style="position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:9999;"></div>
            <div id="CybotCookiebotDialog" style="position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:white;padding:32px;border-radius:8px;box-shadow:0 4px 20px rgba(0,0,0,0.3);z-index:10000;max-width:500px;width:90%;">
                <h3 style="margin-bottom:12px;">This website uses cookies</h3>
                <p style="font-size:14px;color:#555;margin-bottom:20px;">We use cookies to personalise content and ads, to provide social media features and to analyse our traffic.</p>
                <div style="display:flex;gap:12px;flex-wrap:wrap;">
                    <button id="CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll" onclick="document.getElementById('CybotCookiebotDialog').style.display='none';document.getElementById('CybotCookiebotDialogBodyUnderlay').style.display='none';" style="background:#00a050;color:white;border:none;padding:12px 24px;border-radius:4px;cursor:pointer;font-size:14px;flex:1;">Allow All</button>
                    <button id="CybotCookiebotDialogBodyButtonDecline" style="background:white;border:1px solid #ccc;padding:12px 24px;border-radius:4px;cursor:pointer;font-size:14px;flex:1;">Deny</button>
                </div>
            </div>
        </body>
        </html>
    """.trimIndent()

    val COOKIE_CUSTOM_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>Global News Portal</title>
            <style>
                body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; padding: 24px; }
                .custom-cookie-popup { position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 10000; }
                .custom-cookie-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: #1a202c; }
                .custom-cookie-dialog { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); background: #2d3748; color: white; padding: 32px; border-radius: 12px; z-index: 10001; max-width: 500px; width: 90%; text-align: center; }
                .custom-cookie-dialog h2 { margin-bottom: 12px; font-size: 20px; }
                .custom-cookie-dialog p { font-size: 14px; color: #cbd5e0; margin-bottom: 20px; }
                .cookie-accept-custom { background: #48bb78; color: white; border: none; padding: 12px 32px; border-radius: 6px; cursor: pointer; font-size: 16px; font-weight: bold; }
                .cookie-decline-custom { background: transparent; border: 1px solid #718096; color: #a0aec0; padding: 12px 24px; border-radius: 6px; cursor: pointer; font-size: 14px; margin-left: 8px; }
            </style>
        </head>
        <body>
            <h1>Global News Portal</h1>
            <p>Breaking news from around the world.</p>
            <p>Today's access code is: <strong>CUSTOM-BANNER-77</strong></p>
            <p>Updated hourly. Subscribe for premium content.</p>
            <div class="custom-cookie-popup"><div class="custom-cookie-backdrop"></div><div class="custom-cookie-dialog"><h2>Cookie Notice</h2><p>We use cookies to personalise your experience on our site. Please accept cookies to continue browsing.</p><button class="cookie-accept-custom" onclick="document.querySelector('.custom-cookie-popup').style.display='none'">Accept Cookies</button><button class="cookie-decline-custom">Manage Preferences</button></div></div>
        </body>
        </html>
    """.trimIndent()

    val LONG_PAGE_BOTTOM_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>InfraOps — System Administration Guide</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 24px; color: #333; line-height: 1.7; }
                h1 { border-bottom: 2px solid #2c3e50; padding-bottom: 12px; }
                h2 { color: #2c3e50; margin-top: 40px; }
                .section { margin: 24px 0; padding: 20px; background: #f8f9fa; border-left: 4px solid #3498db; }
                table { width: 100%; border-collapse: collapse; margin: 16px 0; }
                th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
                th { background: #ecf0f1; }
                .answer-section { margin-top: 40px; padding: 20px; background: #e8f5e9; border: 1px solid #4caf50; border-radius: 8px; }
            </style>
        </head>
        <body>
            <h1>InfraOps System Administration Guide</h1>
            <p>Version 4.2.1 — Last updated March 2024</p>
            <h2>1. Network Configuration</h2>
            <div class="section"><p>The default gateway is configured at 10.0.0.1 with a subnet mask of 255.255.255.0. DNS servers should point to 10.0.1.53 (primary) and 10.0.1.54 (secondary). VLAN tagging is enabled on ports 1-24 of the core switch.</p><table><tr><th>Parameter</th><th>Value</th></tr><tr><td>Gateway</td><td>10.0.0.1</td></tr><tr><td>Subnet</td><td>255.255.255.0</td></tr><tr><td>DNS Primary</td><td>10.0.1.53</td></tr><tr><td>DNS Secondary</td><td>10.0.1.54</td></tr><tr><td>MTU</td><td>9000 (jumbo frames)</td></tr></table></div>
            <h2>2. Storage Management</h2>
            <div class="section"><p>The primary NAS cluster operates on ZFS with RAIDZ2 redundancy across 12 disks. Total raw capacity is 144 TB with 96 TB usable.</p><table><tr><th>Pool</th><th>Type</th><th>Raw</th><th>Usable</th><th>Used</th></tr><tr><td>pool-main</td><td>RAIDZ2</td><td>144 TB</td><td>96 TB</td><td>61 TB (64%)</td></tr><tr><td>pool-archive</td><td>Mirror</td><td>48 TB</td><td>24 TB</td><td>18 TB (75%)</td></tr><tr><td>pool-scratch</td><td>Stripe</td><td>8 TB</td><td>8 TB</td><td>2 TB (25%)</td></tr></table></div>
            <h2>3. User Access Policies</h2>
            <div class="section"><p>All user accounts are managed through LDAP with Kerberos authentication. Password policy requires minimum 16 characters. Passwords expire every 90 days. Failed login attempts are locked after 5 tries for 30 minutes.</p></div>
            <h2>4. Monitoring &amp; Alerting</h2>
            <div class="section"><p>Prometheus scrapes metrics every 15 seconds from all 247 endpoints.</p><table><tr><th>Severity</th><th>Response Time</th><th>Channel</th></tr><tr><td>P1 — Critical</td><td>5 minutes</td><td>PagerDuty + Phone</td></tr><tr><td>P2 — High</td><td>30 minutes</td><td>PagerDuty</td></tr><tr><td>P3 — Medium</td><td>4 hours</td><td>Slack #alerts</td></tr><tr><td>P4 — Low</td><td>Next business day</td><td>Slack #alerts-low</td></tr></table></div>
            <h2>5. Deployment Procedures</h2>
            <div class="section"><p>All deployments follow the blue-green strategy. Code coverage must exceed 80% for merge approval.</p></div>
            <h2>6. Disaster Recovery</h2>
            <div class="section"><p>RPO: 15 minutes for Tier 1 services. RTO: 30 minutes for Tier 1. DR drills are conducted quarterly.</p></div>
            <h2>7. System Maintenance</h2>
            <div class="answer-section"><p>Scheduled maintenance windows are every Sunday 02:00–06:00 UTC. Emergency maintenance requires VP-level approval.</p><p>The system maintenance code is: <strong>MAINT-PHOENIX-2024-X9</strong></p><p>Use this code when filing maintenance tickets in ServiceNow.</p></div>
        </body>
        </html>
    """.trimIndent()

    val LONG_PAGE_ACCORDION_BOTTOM_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>AcmePower — Safety Operations Manual</title>
            <style>
                body { font-family: Georgia, serif; max-width: 800px; margin: 0 auto; padding: 24px; color: #333; line-height: 1.7; }
                h1 { color: #c0392b; border-bottom: 3px solid #c0392b; padding-bottom: 12px; }
                h2 { color: #2c3e50; margin-top: 36px; }
                .content-block { margin: 20px 0; padding: 16px; background: #fdf2e9; border-radius: 8px; }
                .warning-box { background: #fdedec; border: 2px solid #e74c3c; padding: 16px; border-radius: 8px; margin: 16px 0; }
                .faq-section { margin-top: 40px; }
                .faq-item { border: 1px solid #bdc3c7; margin: 10px 0; border-radius: 6px; overflow: hidden; }
                .faq-q { padding: 14px 18px; cursor: pointer; background: #ecf0f1; border: none; width: 100%; text-align: left; font-size: 15px; font-weight: 600; }
                .faq-q:hover { background: #d5dbdb; }
                .faq-a { padding: 14px 18px; border-top: 1px solid #ddd; font-size: 14px; line-height: 1.6; }
                table { width: 100%; border-collapse: collapse; margin: 16px 0; }
                th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
                th { background: #2c3e50; color: white; }
            </style>
        </head>
        <body>
            <h1>AcmePower Safety Operations Manual</h1>
            <p>Document ID: SOM-2024-R3 | Classification: Internal | Effective: January 1, 2024</p>
            <h2>1. General Safety Principles</h2>
            <div class="content-block"><p>All personnel must complete the Annual Safety Certification (ASC) before operating any equipment rated above 480V. The certification consists of a 40-hour classroom module, 16 hours of hands-on training, and a written exam (minimum passing score: 85%).</p><p>PPE requirements vary by zone classification. Zone A (high voltage): Arc flash suit, insulated gloves, face shield, and steel-toe boots.</p></div>
            <h2>2. Equipment Inspection Schedule</h2>
            <div class="content-block"><table><tr><th>Equipment</th><th>Frequency</th><th>Team</th><th>Form</th></tr><tr><td>Main Transformer</td><td>Monthly</td><td>HV Engineering</td><td>INS-001</td></tr><tr><td>Circuit Breakers</td><td>Quarterly</td><td>Protection</td><td>INS-002</td></tr><tr><td>Backup Generators</td><td>Weekly</td><td>Facilities</td><td>INS-003</td></tr><tr><td>UPS Systems</td><td>Monthly</td><td>IT Infra</td><td>INS-004</td></tr><tr><td>Fire Suppression</td><td>Semi-annual</td><td>Safety</td><td>INS-005</td></tr></table></div>
            <h2>3. Incident Classification</h2>
            <div class="content-block"><table><tr><th>Level</th><th>Description</th><th>Response</th><th>Notification</th></tr><tr><td>Level 1</td><td>Immediate danger</td><td>Immediate</td><td>CEO, COO, Safety Dir.</td></tr><tr><td>Level 2</td><td>Significant damage</td><td>15 min</td><td>Plant Mgr, Safety</td></tr><tr><td>Level 3</td><td>Minor malfunction</td><td>1 hour</td><td>Shift Supervisor</td></tr><tr><td>Level 4</td><td>Documentation error</td><td>Next shift</td><td>Team Lead</td></tr></table></div>
            <h2>4. Environmental Compliance</h2>
            <div class="content-block"><p>All operations must comply with EPA regulations 40 CFR Parts 260-273. Monthly emissions reports are filed electronically through the CEDRI system.</p></div>
            <h2>5. Training Requirements</h2>
            <div class="content-block"><p>New hire orientation includes 3 days of safety training covering: lockout/tagout (LOTO), confined space entry, hot work permits, fall protection, and hazardous material handling.</p></div>
            <div class="warning-box"><strong>IMPORTANT:</strong> All personnel must review and acknowledge the updated safety protocols before their next shift.</div>
            <h2>6. Frequently Asked Questions — Emergency Procedures</h2>
            <div class="faq-section">
                <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What is the evacuation route for Building 3?</button><div class="faq-a" style="display:none">Exit through the east corridor to Assembly Point C (parking lot C). Alternate route: south stairwell to Assembly Point D.</div></div>
                <div class="faq-item"><button class="faq-q" onclick="toggle(this)">What is the emergency shutdown procedure?</button><div class="faq-a" style="display:none">The emergency shutdown procedure code is <strong>ESHUT-DELTA-7X</strong>. Steps: (1) Press the Emergency Power Off (EPO) button. (2) Call the Control Room at ext. 5555. (3) Announce "Emergency Shutdown initiated" on the PA system. (4) Evacuate all non-essential personnel. (5) Wait for the All-Clear from the Safety Director.</div></div>
                <div class="faq-item"><button class="faq-q" onclick="toggle(this)">How do I report a near-miss incident?</button><div class="faq-a" style="display:none">File a Near-Miss Report (Form NMR-100) within 24 hours of the event.</div></div>
                <div class="faq-item"><button class="faq-q" onclick="toggle(this)">Where are the first aid kits located?</button><div class="faq-a" style="display:none">First aid kits are located at: Building 1 — Lobby, Floor 2 break room. Building 2 — Each lab entrance. Building 3 — Control room.</div></div>
            </div>
            <p style="margin-top: 40px; color: #888; font-size: 12px;">AcmePower Inc. — Document Control: Safety Operations — Rev 3.0</p>
            <script>function toggle(btn) { var a = btn.nextElementSibling; a.style.display = a.style.display === 'none' ? 'block' : 'none'; }</script>
        </body>
        </html>
    """.trimIndent()

    val SEARCHABLE_TABLE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <title>OrderFlow — Service Tier SLAs</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 1000px; margin: 0 auto; padding: 24px; color: #333; }
                h1 { color: #1a237e; }
                .intro { background: #e8eaf6; padding: 16px; border-radius: 8px; margin: 16px 0; }
                table { width: 100%; border-collapse: collapse; margin: 24px 0; }
                th { background: #1a237e; color: white; padding: 12px; text-align: left; }
                td { border: 1px solid #ddd; padding: 10px 12px; }
                tr:nth-child(even) { background: #f5f5f5; }
                .footer { margin-top: 32px; color: #888; font-size: 12px; }
            </style>
        </head>
        <body>
            <h1>OrderFlow — Service Tier SLA Reference</h1>
            <div class="intro"><p>This document lists all service tiers and their processing time guarantees.</p></div>
            <table>
                <thead><tr><th>#</th><th>Tier Name</th><th>Monthly Volume</th><th>Processing Time</th><th>Priority Queue</th><th>Dedicated Rep</th><th>SLA Penalty</th></tr></thead>
                <tbody>
                    <tr><td>1</td><td>Micro</td><td>1–10 orders</td><td>72 hours</td><td>No</td><td>No</td><td>None</td></tr>
                    <tr><td>2</td><td>Starter</td><td>11–50 orders</td><td>48 hours</td><td>No</td><td>No</td><td>None</td></tr>
                    <tr><td>3</td><td>Basic</td><td>51–100 orders</td><td>36 hours</td><td>No</td><td>No</td><td>5% credit</td></tr>
                    <tr><td>4</td><td>Basic Plus</td><td>101–200 orders</td><td>30 hours</td><td>No</td><td>No</td><td>5% credit</td></tr>
                    <tr><td>5</td><td>Standard</td><td>201–350 orders</td><td>24 hours</td><td>No</td><td>No</td><td>10% credit</td></tr>
                    <tr><td>6</td><td>Standard Plus</td><td>351–500 orders</td><td>20 hours</td><td>No</td><td>No</td><td>10% credit</td></tr>
                    <tr><td>7</td><td>Professional</td><td>501–750 orders</td><td>16 hours</td><td>Yes</td><td>No</td><td>10% credit</td></tr>
                    <tr><td>8</td><td>Professional Plus</td><td>751–1,000 orders</td><td>14 hours</td><td>Yes</td><td>No</td><td>10% credit</td></tr>
                    <tr><td>9</td><td>Business</td><td>1,001–1,500 orders</td><td>12 hours</td><td>Yes</td><td>No</td><td>15% credit</td></tr>
                    <tr><td>10</td><td>Business Plus</td><td>1,501–2,000 orders</td><td>10 hours</td><td>Yes</td><td>No</td><td>15% credit</td></tr>
                    <tr><td>11</td><td>Growth</td><td>2,001–3,000 orders</td><td>8 hours</td><td>Yes</td><td>No</td><td>15% credit</td></tr>
                    <tr><td>12</td><td>Growth Plus</td><td>3,001–4,000 orders</td><td>7 hours</td><td>Yes</td><td>Shared</td><td>15% credit</td></tr>
                    <tr><td>13</td><td>Scale</td><td>4,001–5,000 orders</td><td>6 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                    <tr><td>14</td><td>Scale Plus</td><td>5,001–7,500 orders</td><td>5 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                    <tr><td>15</td><td>Silver</td><td>7,501–10,000 orders</td><td>4 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                    <tr><td>16</td><td>Silver Plus</td><td>10,001–15,000 orders</td><td>3.5 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                    <tr><td>17</td><td>Gold</td><td>15,001–20,000 orders</td><td>3 hours</td><td>Yes</td><td>Yes</td><td>25% credit</td></tr>
                    <tr><td>18</td><td>Gold Plus</td><td>20,001–30,000 orders</td><td>2.5 hours</td><td>Yes</td><td>Yes</td><td>25% credit</td></tr>
                    <tr><td>19</td><td>Platinum</td><td>30,001–50,000 orders</td><td>2 hours</td><td>Yes</td><td>Yes</td><td>30% credit</td></tr>
                    <tr><td>20</td><td>Platinum Plus</td><td>50,001–75,000 orders</td><td>1.5 hours</td><td>Yes</td><td>Yes</td><td>30% credit</td></tr>
                    <tr><td>21</td><td>Diamond</td><td>75,001–100,000 orders</td><td>1 hour</td><td>Yes</td><td>Yes</td><td>35% credit</td></tr>
                    <tr><td>22</td><td>Diamond Plus</td><td>100,001–150,000 orders</td><td>45 minutes</td><td>Yes</td><td>Yes</td><td>35% credit</td></tr>
                    <tr><td>23</td><td>Elite</td><td>150,001–250,000 orders</td><td>30 minutes</td><td>Yes</td><td>Yes</td><td>40% credit</td></tr>
                    <tr><td>24</td><td>Elite Plus</td><td>250,001–500,000 orders</td><td>20 minutes</td><td>Yes</td><td>Yes</td><td>40% credit</td></tr>
                    <tr><td>25</td><td>Ultimate</td><td>500,001+ orders</td><td>15 minutes</td><td>Yes</td><td>Yes</td><td>50% credit</td></tr>
                </tbody>
            </table>
            <p class="footer">OrderFlow Inc. — SLA Document v3.7 — Effective January 2024</p>
        </body>
        </html>
    """.trimIndent()
}
