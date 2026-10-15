package milu.viewmodel.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class ViewModelIssueRegistry : IssueRegistry() {
    override val api: Int = CURRENT_API
    override val minApi: Int = 16
    override val issues: List<Issue> = ViewModelDetector.issues
    override val vendor = Vendor(
        vendorName = "AndroidViewModel",
        identifier = "milu.viewmodel.lint",
        feedbackUrl = "https://github.com/lwj1994/android_view_model/issues",
    )
}
