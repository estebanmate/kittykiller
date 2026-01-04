const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI, SchemaType } = require("@google/generative-ai");

// IMPORTANTE: Usaremos variables de entorno para la API KEY
const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

exports.procesarDocumentoTest = onRequest({ cors: true, secrets: ["GEMINI_API_KEY"] }, async (req, res) => {
  // CORS ya está habilitado con { cors: true } arriba, pero por seguridad doble:
  if (req.method === "OPTIONS") {
    res.set("Access-Control-Allow-Methods", "POST");
    res.set("Access-Control-Allow-Headers", "Content-Type");
    res.status(204).send("");
    return;
  }

  try {
    const { mode, content } = req.body;

    if (!content) {
      res.status(400).json({ error: "Falta el contenido del documento." });
      return;
    }

    // En kittykiller-backend/functions/index.js

    const model = genAI.getGenerativeModel({
      model: "gemini-2.5-flash-lite", // (Ojo: verifica si este modelo existe, lo estándar es gemini-1.5-flash)
      generationConfig: {
        responseMimeType: "application/json",
        responseSchema: {
          type: SchemaType.ARRAY,
          items: {
            type: SchemaType.OBJECT,
            properties: {
              id: { type: SchemaType.INTEGER },
              pregunta: { type: SchemaType.STRING },
              opciones: {
                type: SchemaType.ARRAY,
                items: { type: SchemaType.STRING }
              },
              // --- CAMBIO AQUÍ ---
              respuesta_correcta: {
                type: SchemaType.STRING,
                enum: ["A", "B", "C", "D"] // Obligamos a que sea una de estas 4 letras
              },
              // -------------------
            },
            required: ["pregunta", "opciones", "respuesta_correcta"],
          },
        },
      },
    });

    let systemInstruction = "";
    if (mode === "extract") {
      systemInstruction = `
        Eres un experto digitalizador de exámenes.
        Extrae las preguntas del texto proporcionado.
        Salida JSON estricta.
        Campo 'respuesta_correcta': Solo la letra mayúscula (A, B, C, D).
        Si no hay respuesta, deduce la más probable.
      `;
    } else {
      systemInstruction = `
        Genera un examen test de 20 preguntas nivel TCAE basado en el texto.
        Campo 'respuesta_correcta': Solo la letra mayúscula (A, B, C, D).
        Salida JSON estricta.
      `;
    }

    const chat = model.startChat({
      history: [{ role: "user", parts: [{ text: systemInstruction }] }]
    });

    const result = await chat.sendMessage(content);
    const jsonResponse = JSON.parse(result.response.text());

    res.status(200).json({
      success: true,
      data: jsonResponse
    });

  } catch (error) {
    console.error("Error:", error);
    res.status(500).json({ success: false, error: error.message });
  }
});
