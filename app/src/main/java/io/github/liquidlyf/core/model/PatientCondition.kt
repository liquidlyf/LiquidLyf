package io.github.liquidlyf.core.model

/** Optional reason for a request, shown as a chip (doc 07 §1). [code] is the stored Firestore value. */
enum class PatientCondition(val code: String) {
    SURGERY("SURGERY"),
    ACCIDENT("ACCIDENT"),
    THALASSEMIA("THALASSEMIA"),
    DENGUE("DENGUE"),
    DELIVERY("DELIVERY"),
    CANCER("CANCER"),
    ANEMIA("ANEMIA"),
    OTHER("OTHER"),
    ;

    companion object {
        fun fromCode(code: String?): PatientCondition? = entries.firstOrNull { it.code == code }
    }
}
