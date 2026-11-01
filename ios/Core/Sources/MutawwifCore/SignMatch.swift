import Foundation

// Telling near-identical buildings apart by reading their entrance plaques.
//
// ## The problem this exists for
//
// On this campus the colleges are visually interchangeable: the same sandstone
// facade, the same pointed arches, built to one template. Neither a person nor
// image matching can name one from the architecture, and GPS cannot separate
// them either -- the entrances sit inside the error of a phone fix. What *is*
// distinctive is the plaque above every door, in Arabic and English.
//
// ## Why plain word overlap is not enough
//
//     College of Noble Quran and Islamic Studies
//     College of Hadith and Islamic Studies
//
// Five of seven words are shared. Counting matching words would score them
// nearly equally and the app would confidently name the wrong building -- the
// worst outcome available. So words are weighted by how *rare* they are across
// the candidate set (inverse document frequency): "college", "islamic",
// "كلية" count for almost nothing; "hadith", "quran", "الشريعة" carry the
// decision. Weights come from whichever buildings are in contention.
//
// ## Why it refuses to guess
//
// A match must beat the runner-up by a clear margin. Returning `.unsure` costs
// the user a second look; returning a wrong name costs them the walk.

/// How confident the reader is about which building it is looking at.
public enum SignMatch: Equatable {
    /// One building clearly won. `margin`: how far clear of the runner-up, 0..1.
    case found(landmark: Landmark, score: Double, margin: Double)
    /// Text was read, but it does not clearly name any candidate.
    case unsure(bestGuess: Landmark?, reason: String)
    /// Nothing legible in the frame.
    case noText
}

/// Minimum weighted score for a match to be considered at all.
///
/// Below this the reader has essentially only matched filler words like
/// "college of", which every building shares.
public let SIGN_MIN_SCORE: Double = 0.34

/// How far ahead of the runner-up the winner must be.
///
/// Set high on purpose. The failure this guards against is naming the wrong one
/// of two near-identical neighbours, which is exactly the situation the feature
/// exists for.
public let SIGN_MIN_MARGIN: Double = 0.25

/// Words too common to carry meaning, stripped before scoring.
let STOP_WORDS: Set<String> = ["of", "and", "the", "for", "in", "في", "من", "و", "ال"]

private func isLetterOrNumber(_ s: Unicode.Scalar) -> Bool {
    switch s.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
         .decimalNumber, .letterNumber, .otherNumber:
        return true
    default:
        return false
    }
}

/// Splits text into comparable tokens (Kotlin: split on `[^\p{L}\p{N}]+`).
///
/// Reuses `normaliseForSearch` so Arabic folds exactly the way it does
/// everywhere else in the app -- alef variants, taa marbuta and tashkeel all
/// normalise, which matters because OCR is inconsistent about them.
public func signTokens(_ text: String) -> [String] {
    var tokens: [String] = []
    var current = String.UnicodeScalarView()
    for s in normaliseForSearch(text).unicodeScalars {
        if isLetterOrNumber(s) {
            current.append(s)
        } else {
            tokens.append(String(current))
            current = String.UnicodeScalarView()
        }
    }
    tokens.append(String(current))
    return tokens.filter { $0.utf16.count > 1 && !STOP_WORDS.contains($0) }
}

/// Everything a landmark might have written on its sign.
///
/// The name is included alongside the explicit sign texts, so a landmark that
/// nobody has photographed a plaque for still has something to match.
private func corpusFor(_ landmark: Landmark) -> [String] {
    return (landmark.signTexts + [landmark.name])
        .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// Matches text read off a sign against the buildings it could belong to.
///
/// - Parameters:
///   - recognised: the lines OCR returned, in any order
///   - candidates: the buildings in contention -- normally the handful within
///     GPS range, not the whole campus, since narrowing by position first makes
///     the rare-word weighting sharper
public func matchSign(_ recognised: [String], _ candidates: [Landmark]) -> SignMatch {
    let readTokens = Set(recognised.flatMap { signTokens($0) })
    if readTokens.isEmpty { return .noText }
    if candidates.isEmpty { return .unsure(bestGuess: nil, reason: "لا توجد مبانٍ قريبة للمقارنة") }

    // Document frequency across the candidates: how many buildings use each
    // word. A word on every sign tells us nothing. Kept in candidate order
    // (Kotlin associateWith is insertion-ordered) so ties rank identically.
    var documentFrequency: [String: Int] = [:]
    var candidateTokens: [(Landmark, Set<String>)] = []
    for landmark in candidates {
        let tokens = Set(corpusFor(landmark).flatMap { signTokens($0) })
        for t in tokens { documentFrequency[t, default: 0] += 1 }
        if let idx = candidateTokens.firstIndex(where: { $0.0 == landmark }) {
            candidateTokens[idx].1 = tokens
        } else {
            candidateTokens.append((landmark, tokens))
        }
    }

    let total = candidates.count
    func weight(_ token: String) -> Double {
        let df = documentFrequency[token] ?? 0
        if df == 0 { return 0.0 }
        // Smoothed IDF. With one candidate every weight collapses to a
        // constant, which is correct -- there is nothing to discriminate
        // against, so any real overlap should win.
        return log(1.0 + Double(total) / Double(df))
    }

    // Score by how much of the DISTINCTIVE evidence in the read text points at
    // each candidate. Not "what fraction of the candidate's name did we read":
    // every plaque carries an Arabic and an English line, so reading one line
    // cleanly covers only half the stored text. Partial reads are normal.
    let achieved: [(Landmark, Double)] = candidateTokens.map { entry in
        (entry.0, entry.1.filter { readTokens.contains($0) }.reduce(0.0) { $0 + weight($1) })
    }

    // Weight of what we read that belongs to ANY candidate. Text from a fire
    // exit sign or a passing bus is not evidence for or against anything.
    let attributable = readTokens.filter { documentFrequency[$0] != nil }.reduce(0.0) { $0 + weight($1) }
    if attributable <= 0.0 {
        return .unsure(bestGuess: nil, reason: "لم أتعرف على اسم واضح في اللوحة")
    }

    // Stable descending sort, like Kotlin sortedByDescending.
    let ranked = achieved.enumerated()
        .sorted { a, b in
            if a.element.1 != b.element.1 { return a.element.1 > b.element.1 }
            return a.offset < b.offset
        }
        .map { $0.element }
    let best = ranked[0].0
    let bestWeight = ranked[0].1
    let runnerUpWeight = ranked.count > 1 ? ranked[1].1 : 0.0

    if bestWeight <= 0.0 {
        return .unsure(bestGuess: nil, reason: "لم أتعرف على اسم واضح في اللوحة")
    }

    // Words unique to a single building are what actually decide this. If two
    // different buildings each have one of theirs in frame, the camera is
    // looking at two plaques and no answer about "the" building is honest.
    let namedExclusively = candidateTokens.filter { entry in
        entry.1.contains { readTokens.contains($0) && documentFrequency[$0] == 1 }
    }.count
    if namedExclusively > 1 {
        return .unsure(bestGuess: best, reason: "أرى أكثر من لافتة — قرّب الكاميرا على واحدة")
    }

    let score = bestWeight / attributable
    let margin = 1.0 - (runnerUpWeight / bestWeight)

    if score < SIGN_MIN_SCORE {
        return .unsure(bestGuess: nil, reason: "لم أتعرف على اسم واضح في اللوحة")
    }
    if margin < SIGN_MIN_MARGIN {
        // Deliberately names the ambiguity rather than picking one. Two
        // buildings whose signs share their distinctive words are exactly the
        // case where a confident answer would be a lie.
        return .unsure(bestGuess: best, reason: "اللوحة تشبه أكثر من مبنى — قرّب الكاميرا من اللافتة")
    }
    return .found(landmark: best, score: score, margin: margin)
}
