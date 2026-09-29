package expo.modules.cratecanvas

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class CrateCanvasModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("CrateCanvas")

    View(CrateCanvasView::class) {
      Events("onCoverPress", "onClose")

      Prop("covers") { view: CrateCanvasView, covers: List<String> ->
        view.setCovers(covers)
      }

      Prop("closeSignal") { view: CrateCanvasView, value: Int ->
        view.setCloseSignal(value)
      }

    }
  }
}
