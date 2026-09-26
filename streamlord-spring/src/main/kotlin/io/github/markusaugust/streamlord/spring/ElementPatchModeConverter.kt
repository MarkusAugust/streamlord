package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import org.springframework.core.convert.converter.Converter

/**
 * Lets Spring bind [ElementPatchMode] from its wire token (`"append"`) in `@PathVariable` and
 * `@RequestParam`, instead of only from the enum constant name. Register it as a bean, or add
 * it in `WebMvcConfigurer.addFormatters`; Streamlord ships no auto-configuration on purpose.
 */
public class ElementPatchModeConverter : Converter<String, ElementPatchMode> {
    override fun convert(source: String): ElementPatchMode = ElementPatchMode.fromWire(source)
}
