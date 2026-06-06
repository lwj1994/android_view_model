package milu.viewmodel.example

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button as ComposeButton
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentActivity
import milu.viewmodel.ViewModelBindingProvider
import milu.viewmodel.activityViewModelBinding
import milu.viewmodel.rememberRetainedViewModelBinding
import milu.viewmodel.viewLifecycleViewModelBinding
import milu.viewmodel.viewModelBinding
import milu.viewmodel.watchViewModel

class MainActivity : FragmentActivity() {
    private val plainController = PlainCounterController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeViewId = View.generateViewId()
        val fragmentContainerId = View.generateViewId()
        val customViewId = View.generateViewId()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(24, 24, 24, 24)
        }

        val composeHost = androidx.compose.ui.platform.ComposeView(this).apply {
            id = composeViewId
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setContent {
                MaterialTheme {
                    CounterScreen(
                        onPlainClassClick = { plainController.incrementFromPlainClass() },
                    )
                }
            }
        }

        val customCounterView = CounterPanelView(this).apply {
            id = customViewId
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val fragmentHost = FragmentContainerView(this).apply {
            id = fragmentContainerId
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        root.addView(composeHost)
        root.addView(customCounterView)
        root.addView(fragmentHost)
        setContentView(root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(fragmentContainerId, CounterFragment())
                .commitNow()
        }

        val counter = viewModelBinding.watch(counterSpec)
        viewModelBinding.addUpdateListener {
            title = "Count ${counter.state.count}"
        }
    }

    override fun onDestroy() {
        plainController.close()
        super.onDestroy()
    }
}

@Composable
private fun CounterScreen(onPlainClassClick: () -> Unit) {
    ViewModelBindingProvider(binding = rememberRetainedViewModelBinding()) {
        val counter = watchViewModel(counterSpec)
        val analytics = watchViewModel(analyticsSpec)

        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = counter.state.label, style = MaterialTheme.typography.h6)
                Text(text = "Compose count: ${counter.state.count}")
                Text(text = "Last event: ${analytics.lastEvent}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComposeButton(onClick = { counter.increment("compose") }) {
                        Text("Compose +1")
                    }
                    ComposeButton(onClick = onPlainClassClick) {
                        Text("Plain class +1")
                    }
                    ComposeButton(onClick = counter::reset) {
                        Text("Reset")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
            }
        }
    }
}

class CounterFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): android.view.View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 18, 0, 18)
        }
        val title = TextView(requireContext())
        val button = Button(requireContext()).apply {
            text = "Fragment +1"
        }
        root.addView(title)
        root.addView(button)
        return root
    }

    override fun onViewCreated(
        view: android.view.View,
        savedInstanceState: Bundle?,
    ) {
        val counter = viewLifecycleViewModelBinding.watch(counterSpec)
        val activityCounter = activityViewModelBinding.read(counterSpec)
        val title = (view as LinearLayout).getChildAt(0) as TextView
        val button = view.getChildAt(1) as Button

        fun render() {
            title.text = "Fragment count: ${counter.state.count}"
        }
        render()

        button.setOnClickListener {
            activityCounter.increment("fragment")
        }
        viewLifecycleViewModelBinding.addUpdateListener(::render)
    }
}

class CounterPanelView(context: android.content.Context) : LinearLayout(context) {
    private val title = TextView(context)
    private val button = Button(context).apply {
        text = "View +1"
    }

    init {
        orientation = VERTICAL
        setPadding(0, 18, 0, 18)
        addView(title)
        addView(button)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val counter = viewModelBinding.watch(counterSpec)

        fun render() {
            title.text = "Custom View count: ${counter.state.count}"
        }
        render()

        button.setOnClickListener {
            counter.increment("custom view")
        }
        viewModelBinding.addUpdateListener(::render)
    }
}
